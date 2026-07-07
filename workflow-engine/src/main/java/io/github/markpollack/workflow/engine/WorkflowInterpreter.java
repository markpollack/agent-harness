package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.spec.AlwaysCondition;
import io.github.markpollack.workflow.spec.Binding;
import io.github.markpollack.workflow.spec.DecisionResultCondition;
import io.github.markpollack.workflow.spec.DecisionSpecNode;
import io.github.markpollack.workflow.spec.ErrorCondition;
import io.github.markpollack.workflow.spec.ErrorMatch;
import io.github.markpollack.workflow.spec.OperationDeclaration;
import io.github.markpollack.workflow.spec.PolicyBundle;
import io.github.markpollack.workflow.spec.RetryPolicySpec;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.TerminateSpecNode;
import io.github.markpollack.workflow.spec.TimeoutPolicySpec;
import io.github.markpollack.workflow.spec.ValidationError;
import io.github.markpollack.workflow.spec.WorkflowEdgeSpec;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import io.github.markpollack.workflow.spec.WorkflowSpecNode;
import io.github.markpollack.workflow.spec.WorkflowSpecValidationException;
import io.github.markpollack.workflow.spec.WorkflowSpecValidator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The control plane (DD-16): reads the inert spec and decides everything that happens
 * next — bindings, dispatch, retry policy (DD-17), context writes, edge selection,
 * events — per the alpha spec's §12–§17 semantics. Handlers execute exactly one
 * attempt; this class owns the loop, the waits, and the §19 defensive boundary
 * (a throwing or null-returning handler normalizes to
 * {@code failure(UNHANDLED_EXCEPTION, retryable=false)} and flows through ordinary
 * precedence).
 *
 * <p>Non-durable, in-memory alpha interpreter: the canonical sink receives events in
 * deterministic execution order with monotonic sequences (§10); durability
 * (CheckpointStore, resume, INTERRUPTED normalization) arrives in Stage 3.
 */
public final class WorkflowInterpreter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final OperationRegistry registry;
    private final WorkflowEventSink sink;
    private final Clock clock;
    private final WorkflowSpecValidator validator = new WorkflowSpecValidator();

    public WorkflowInterpreter(OperationRegistry registry, WorkflowEventSink sink) {
        this(registry, sink, Clock.systemUTC());
    }

    public WorkflowInterpreter(OperationRegistry registry, WorkflowEventSink sink, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Runs with a fresh context carrying only the run id. */
    public WorkflowRunOutcome run(WorkflowSpec spec, String workflowRunId, Object input) {
        return run(spec, workflowRunId, input, AgentContext.withRunId(workflowRunId));
    }

    /**
     * Runs with a caller-seeded context (pre-populated typed keys, e.g. for Step-backed
     * operations). The seed's run id is overridden by {@code workflowRunId}.
     *
     * @throws WorkflowSpecValidationException if the spec fails semantic validation —
     *                                         rejected before any event is emitted (§19)
     */
    public WorkflowRunOutcome run(WorkflowSpec spec, String workflowRunId, Object input,
            AgentContext seedContext) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(seedContext, "seedContext");
        List<ValidationError> errors = validator.validate(spec);
        if (!errors.isEmpty()) {
            throw new WorkflowSpecValidationException(errors);
        }
        AgentContext context = seedContext.mutate()
                .with(AgentContext.WORKFLOW_RUN_ID, workflowRunId)
                .with(AgentContext.WORKFLOW_NAME, spec.metadata().name())
                .build();
        return new Run(spec, workflowRunId, input, context).execute();
    }

    /** §18 portable form derived from metadata: {@code workflow://registry/<name>@<version>}. */
    static String specRef(WorkflowSpec spec) {
        String name = spec.metadata().name();
        String version = spec.metadata().version();
        return "workflow://registry/" + name + (version != null ? "@" + version : "");
    }

    /** Node execution either continues along an edge or ends the workflow. */
    private sealed interface Flow {
        record Continue(String nextNodeId) implements Flow {
        }

        record End(WorkflowRunOutcome outcome) implements Flow {
        }
    }

    /** Per-run state and the execution loop. */
    private final class Run {

        private final WorkflowSpec spec;
        private final String workflowRunId;
        private final WorkflowEventFactory events;
        private final BindingEvaluator evaluator;
        private final Map<String, WorkflowSpecNode> nodesById = new LinkedHashMap<>();
        private AgentContextAdapter store;

        Run(WorkflowSpec spec, String workflowRunId, Object input, AgentContext context) {
            this.spec = spec;
            this.workflowRunId = workflowRunId;
            this.events = new WorkflowEventFactory(workflowRunId, specRef(spec), clock);
            this.evaluator = new BindingEvaluator(input, spec.constants());
            this.store = new AgentContextAdapter(context);
            spec.nodes().forEach(node -> nodesById.put(node.id(), node));
        }

        WorkflowRunOutcome execute() {
            sink.emit(events.workflowStarted(spec.metadata().name(), spec.entrypoint()));
            String current = spec.entrypoint();
            while (true) {
                WorkflowSpecNode node = nodesById.get(current);
                sink.emit(events.nodeStarted(node.id(), nodeKind(node)));
                Flow flow = switch (node) {
                    case TaskSpecNode task -> executeTask(task);
                    case DecisionSpecNode decision -> executeDecision(decision);
                    case TerminateSpecNode terminate -> executeTerminate(terminate);
                };
                if (flow instanceof Flow.End(WorkflowRunOutcome outcome)) {
                    return outcome;
                }
                current = ((Flow.Continue) flow).nextNodeId();
            }
        }

        // ---------------------------------------------------------------------
        // Node execution
        // ---------------------------------------------------------------------

        private Flow executeTask(TaskSpecNode task) {
            BindingResolution input = assembleInput(task.id(), task.input());
            if (input instanceof BindingResolution.Failed(String reason)) {
                return failWorkflow(task.id(), "failed", reason);
            }
            Object inputValue = task.input() == null ? null
                    : ((BindingResolution.Resolved) input).value();

            AttemptOutcome attempt = dispatchWithRetry(task.id(), task.operation(), task.policies(), inputValue);
            if (attempt.flow() != null) {
                return attempt.flow();
            }
            OperationResult result = attempt.result();
            if (!result.successful()) {
                return routeFailure(task.id(), result);
            }

            Object output = ((OperationResult.Success) result).output();
            evaluator.recordOutput(task.id(), output);
            Flow writeFailure = applyContextWrites(task);
            if (writeFailure != null) {
                return writeFailure;
            }
            sink.emit(events.nodeCompleted(task.id(), "succeeded"));
            return routeSuccess(task.id(), null);
        }

        private Flow executeDecision(DecisionSpecNode decision) {
            BindingResolution input = assembleInput(decision.id(), decision.input());
            if (input instanceof BindingResolution.Failed(String reason)) {
                return failWorkflow(decision.id(), "failed", reason);
            }
            Object inputValue = decision.input() == null ? null
                    : ((BindingResolution.Resolved) input).value();

            AttemptOutcome attempt = dispatchWithRetry(decision.id(), decision.operation(),
                    decision.policies(), inputValue);
            if (attempt.flow() != null) {
                return attempt.flow();
            }
            OperationResult result = attempt.result();
            if (!result.successful()) {
                return routeFailure(decision.id(), result);
            }

            Object output = ((OperationResult.Success) result).output();
            String outcome = extractOutcome(output);
            if (outcome == null) {
                return failWorkflow(decision.id(), "failed",
                        "decision operation returned no string 'outcome' field (§6)");
            }
            if (!decision.outcomes().contains(outcome)) {
                return failWorkflow(decision.id(), "failed",
                        "undeclared decision outcome: " + outcome + " (§15)");
            }
            evaluator.recordOutput(decision.id(), output);
            evaluator.recordDecision(decision.id(), outcome);
            sink.emit(events.nodeCompleted(decision.id(), "succeeded"));
            return routeSuccess(decision.id(), outcome);
        }

        private Flow executeTerminate(TerminateSpecNode terminate) {
            Object result = null;
            if (terminate.result() != null) {
                BindingResolution resolution = evaluator.resolve(terminate.result(), store);
                if (resolution instanceof BindingResolution.Failed(String reason)) {
                    sink.emit(events.bindingEvaluated(terminate.id(), "result",
                            terminate.result().from(), false, null));
                    return failWorkflow(terminate.id(), "failed", reason);
                }
                result = ((BindingResolution.Resolved) resolution).value();
                sink.emit(events.bindingEvaluated(terminate.id(), "result",
                        terminate.result().from(), true, ValueDisclosure.metadataOnly(result)));
            }
            sink.emit(events.nodeCompleted(terminate.id(), "succeeded"));

            return switch (terminate.status()) {
                case COMPLETED -> completeWorkflow(terminate.id(), result);
                case FAILED -> endWorkflow("failed", "terminate:" + terminate.id(), result);
                case CANCELLED -> endWorkflow("cancelled", "terminate:" + terminate.id(), result);
                case ABORTED -> endWorkflow("aborted", "terminate:" + terminate.id(), result);
            };
        }

        private Flow completeWorkflow(String nodeId, Object result) {
            Map<String, Object> outputs = null;
            if (spec.outputs() != null) {
                outputs = new LinkedHashMap<>();
                for (Map.Entry<String, Binding> entry : new TreeMap<>(spec.outputs()).entrySet()) {
                    BindingResolution resolution = evaluator.resolve(entry.getValue(), store);
                    if (resolution instanceof BindingResolution.Failed(String reason)) {
                        sink.emit(events.bindingEvaluated(nodeId, entry.getKey(),
                                entry.getValue().from(), false, null));
                        return endWorkflow("failed", reason, result);
                    }
                    Object value = ((BindingResolution.Resolved) resolution).value();
                    sink.emit(events.bindingEvaluated(nodeId, entry.getKey(),
                            entry.getValue().from(), true, ValueDisclosure.metadataOnly(value)));
                    outputs.put(entry.getKey(), value);
                }
            }
            Object disclosed = outputs != null ? outputs : result;
            sink.emit(events.workflowCompleted("completed",
                    disclosed != null ? ValueDisclosure.metadataOnly(disclosed) : null));
            return new Flow.End(new WorkflowRunOutcome("completed", null, result, outputs));
        }

        // ---------------------------------------------------------------------
        // Dispatch and retry (§17)
        // ---------------------------------------------------------------------

        /** Either a terminal flow (cancelled/aborted/unresolvable) or the final attempt result. */
        private record AttemptOutcome(OperationResult result, Flow flow) {
        }

        private AttemptOutcome dispatchWithRetry(String nodeId, String operationAlias,
                PolicyBundle nodePolicies, Object input) {
            OperationDeclaration declaration = spec.operations().get(operationAlias);
            String ref = declaration.ref();
            OperationHandler handler;
            try {
                handler = registry.resolve(ref);
            } catch (UnknownOperationException ex) {
                return new AttemptOutcome(null,
                        failWorkflow(nodeId, "aborted", "unknown operation: " + ex.operationRef()));
            }
            RetryPolicySpec retryPolicy = effective(nodePolicies, declaration.defaultPolicies(),
                    spec.policies(), PolicyBundle::retry);
            TimeoutPolicySpec timeoutPolicy = effective(nodePolicies, declaration.defaultPolicies(),
                    spec.policies(), PolicyBundle::timeout);

            int attempt = 1;
            while (true) {
                sink.emit(events.operationDispatched(nodeId, ref, attempt));
                OperationResult result = executeAttempt(handler,
                        new OperationInvocation(workflowRunId, nodeId, ref, attempt),
                        input, timeoutPolicy);

                if (result.successful()) {
                    Object output = ((OperationResult.Success) result).output();
                    sink.emit(events.operationSucceeded(nodeId, ref, attempt,
                            output != null ? ValueDisclosure.metadataOnly(output) : null));
                    return new AttemptOutcome(result, null);
                }
                if (!result.routable()) {
                    // cancelled/aborted: terminate without error-edge evaluation (§17 rules 10–11)
                    String state = result.status().wireName();
                    String reason = switch (result) {
                        case OperationResult.Cancelled c -> c.reason();
                        case OperationResult.Aborted a -> a.reason();
                        default -> state;
                    };
                    return new AttemptOutcome(null, failWorkflow(nodeId, state, reason));
                }
                sink.emit(events.operationFailed(nodeId, ref, attempt, result));
                RetryDecision decision = RetryDecider.decide(retryPolicy, result, attempt);
                if (decision instanceof RetryDecision.Retry(long delayMillis, String reason)) {
                    sink.emit(events.retryScheduled(nodeId, ref, attempt, reason, delayMillis, retryPolicy));
                    if (!sleep(delayMillis)) {
                        return new AttemptOutcome(null, failWorkflow(nodeId, "aborted",
                                "interrupted while waiting to retry"));
                    }
                    attempt++;
                    continue;
                }
                return new AttemptOutcome(result, null); // exhausted → caller routes error edges
            }
        }

        /** One attempt behind the §19 defensive boundary, with the per-attempt timeout applied. */
        private OperationResult executeAttempt(OperationHandler handler, OperationInvocation invocation,
                Object input, TimeoutPolicySpec timeout) {
            if (timeout == null) {
                return guarded(handler, invocation, input);
            }
            FutureTask<OperationResult> attempt =
                    new FutureTask<>(() -> guarded(handler, invocation, input));
            Thread worker = Thread.ofVirtual()
                    .name("workflow-attempt-" + invocation.nodeId() + "-" + invocation.attemptNumber())
                    .start(attempt);
            try {
                return attempt.get(timeout.perAttemptMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                worker.interrupt();
                return OperationResult.timedOut(ErrorEnvelope.of("OPERATION_TIMEOUT",
                        "Operation exceeded " + timeout.perAttemptMillis() + " ms per-attempt timeout",
                        true));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return OperationResult.aborted("interpreter interrupted during attempt");
            } catch (ExecutionException ex) {
                // unreachable in practice: guarded() never throws — but stay defensive
                return unhandled(ex.getCause() != null ? ex.getCause() : ex);
            }
        }

        private OperationResult guarded(OperationHandler handler, OperationInvocation invocation,
                Object input) {
            try {
                OperationResult result = handler.execute(invocation, store.context(), input);
                return result != null ? result
                        : OperationResult.failure(ErrorEnvelope.of("UNHANDLED_EXCEPTION",
                                "operation handler returned null", false));
            } catch (Exception ex) {
                return unhandled(ex);
            }
        }

        private OperationResult unhandled(Throwable ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return OperationResult.failure(new ErrorEnvelope("UNHANDLED_EXCEPTION", message, false,
                    Map.of("exceptionClass", ex.getClass().getName())));
        }

        private boolean sleep(long delayMillis) {
            if (delayMillis <= 0) {
                return true;
            }
            try {
                Thread.sleep(delayMillis);
                return true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        // ---------------------------------------------------------------------
        // Bindings and context writes (§12–§14)
        // ---------------------------------------------------------------------

        /**
         * Evaluates a node's input map in lexicographic key order (§12); the first
         * failure stops evaluation (§13). Returns {@code Resolved(LinkedHashMap)} —
         * or {@code Resolved(Map.of())} for a null/empty declaration, which callers
         * translate to a null dispatch input.
         */
        private BindingResolution assembleInput(String nodeId, Map<String, Binding> declared) {
            if (declared == null || declared.isEmpty()) {
                return BindingResolution.resolved(Map.of());
            }
            Map<String, Object> assembled = new LinkedHashMap<>();
            for (Map.Entry<String, Binding> entry : new TreeMap<>(declared).entrySet()) {
                BindingResolution resolution = evaluator.resolve(entry.getValue(), store);
                if (resolution instanceof BindingResolution.Failed(String reason)) {
                    sink.emit(events.bindingEvaluated(nodeId, entry.getKey(),
                            entry.getValue().from(), false, null));
                    return BindingResolution.failed(reason);
                }
                Object value = ((BindingResolution.Resolved) resolution).value();
                sink.emit(events.bindingEvaluated(nodeId, entry.getKey(),
                        entry.getValue().from(), true, ValueDisclosure.metadataOnly(value)));
                assembled.put(entry.getKey(), value);
            }
            return BindingResolution.resolved(assembled);
        }

        /** Applies contextWrites in lexicographic key order (§14); null = no failure. */
        private Flow applyContextWrites(TaskSpecNode task) {
            if (task.contextWrites() == null) {
                return null;
            }
            for (Map.Entry<String, Binding> entry : new TreeMap<>(task.contextWrites()).entrySet()) {
                BindingResolution resolution = evaluator.resolve(entry.getValue(), store);
                if (resolution instanceof BindingResolution.Failed(String reason)) {
                    sink.emit(events.bindingEvaluated(task.id(), entry.getKey(),
                            entry.getValue().from(), false, null));
                    return failWorkflow(task.id(), "failed", reason);
                }
                Object value = ((BindingResolution.Resolved) resolution).value();
                ValueDisclosure disclosure = ValueDisclosure.metadataOnly(value);
                sink.emit(events.bindingEvaluated(task.id(), entry.getKey(),
                        entry.getValue().from(), true, disclosure));
                store = store.put(entry.getKey(), value);
                sink.emit(events.contextWriteApplied(task.id(), entry.getKey(),
                        entry.getValue().from(), disclosure));
            }
            return null;
        }

        // ---------------------------------------------------------------------
        // Edge selection (§16) and terminal transitions
        // ---------------------------------------------------------------------

        private Flow routeSuccess(String nodeId, String outcome) {
            List<WorkflowEdgeSpec> matches = new ArrayList<>();
            for (WorkflowEdgeSpec edge : spec.edges()) {
                if (!edge.from().equals(nodeId)) {
                    continue;
                }
                boolean matched = switch (edge.when()) {
                    case AlwaysCondition a -> true;
                    case DecisionResultCondition d -> d.value().equals(outcome);
                    case ErrorCondition e -> false;
                };
                if (matched) {
                    matches.add(edge);
                }
            }
            return selectExactlyOne(nodeId, matches,
                    outcome != null ? "decision_outcome_match" : "always");
        }

        private Flow routeFailure(String nodeId, OperationResult result) {
            sink.emit(events.nodeCompleted(nodeId, "failed"));
            ErrorEnvelope error = switch (result) {
                case OperationResult.Failure f -> f.error();
                case OperationResult.TimedOut t -> t.error();
                default -> throw new IllegalStateException("not routable: " + result.status());
            };
            List<WorkflowEdgeSpec> matches = new ArrayList<>();
            for (WorkflowEdgeSpec edge : spec.edges()) {
                if (edge.from().equals(nodeId)
                        && edge.when() instanceof ErrorCondition(ErrorMatch match)
                        && errorMatches(match, error)) {
                    matches.add(edge);
                }
            }
            if (matches.isEmpty()) {
                return endWorkflow("failed", "unroutable failure: " + error.code(), null);
            }
            return selectExactlyOne(nodeId, matches, "error_match");
        }

        private static boolean errorMatches(ErrorMatch match, ErrorEnvelope error) {
            if (match.code() != null && !match.code().equals(error.code())) {
                return false;
            }
            return match.retryable() == null || match.retryable() == error.retryable();
        }

        private Flow selectExactlyOne(String nodeId, List<WorkflowEdgeSpec> matches, String reason) {
            if (matches.size() == 1) {
                WorkflowEdgeSpec edge = matches.get(0);
                sink.emit(events.edgeSelected(edge, reason));
                return new Flow.Continue(edge.to());
            }
            String detail = matches.isEmpty()
                    ? "no matching edge from node '" + nodeId + "'"
                    : matches.size() + " edges match from node '" + nodeId + "' (§16 requires exactly one)";
            return endWorkflow("failed", detail, null);
        }

        /** Node-level failure: NodeCompleted(failed) then workflow termination. */
        private Flow failWorkflow(String nodeId, String terminalState, String reason) {
            sink.emit(events.nodeCompleted(nodeId, "failed"));
            return endWorkflow(terminalState, reason, null);
        }

        private Flow endWorkflow(String terminalState, String reason, Object result) {
            sink.emit(events.workflowFailed(terminalState, reason));
            return new Flow.End(new WorkflowRunOutcome(terminalState, reason, result, null));
        }

        // ---------------------------------------------------------------------
        // Helpers
        // ---------------------------------------------------------------------

        private static String nodeKind(WorkflowSpecNode node) {
            return switch (node) {
                case TaskSpecNode t -> "task";
                case DecisionSpecNode d -> "decision";
                case TerminateSpecNode t -> "terminate";
            };
        }

        /** §4 precedence: node > operation > workflow, per whole policy kind. */
        private static <P> P effective(PolicyBundle node, PolicyBundle operation,
                PolicyBundle workflow, java.util.function.Function<PolicyBundle, P> kind) {
            if (node != null && kind.apply(node) != null) {
                return kind.apply(node);
            }
            if (operation != null && kind.apply(operation) != null) {
                return kind.apply(operation);
            }
            return workflow != null ? kind.apply(workflow) : null;
        }

        /** §6 decision output shape: an object with a string {@code outcome} field. */
        private static String extractOutcome(Object output) {
            if (output == null) {
                return null;
            }
            Object candidate;
            if (output instanceof Map<?, ?> map) {
                candidate = map.get("outcome");
            } else if (output instanceof JsonNode node) {
                JsonNode field = node.get("outcome");
                candidate = field != null && field.isTextual() ? field.asText() : null;
            } else {
                try {
                    candidate = WorkflowEventJson.mapper().convertValue(output, MAP_TYPE).get("outcome");
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
            return candidate instanceof String s ? s : null;
        }
    }
}
