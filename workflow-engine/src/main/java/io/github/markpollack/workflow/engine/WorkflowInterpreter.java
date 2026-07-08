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
import java.time.Instant;
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
 * <p>Without a {@link CheckpointStore} this is the in-memory alpha interpreter: the
 * canonical sink receives events in deterministic execution order with monotonic
 * sequences (§10). With one, the same control plane becomes durable: progress commits
 * at the §10 boundaries (dispatch, retry-scheduled, node completion, terminal), each
 * boundary an atomic events-plus-state commit, and an interpreter reopened on the same
 * {@code workflowRunId} resumes — committed completed nodes never re-execute (their
 * recorded {@link OperationResult}s replay into bindings and routing); an in-flight
 * attempt normalizes to {@code OperationFailed(code=INTERRUPTED, retryable=true)}
 * through ordinary §17 precedence; a stale committed RetryScheduled fires immediately.
 * The caller re-supplies the same input and seed context on resume (neither is
 * persisted); the run identity pins the spec via {@link CanonicalSpecHash}.
 */
public final class WorkflowInterpreter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final OperationRegistry registry;
    private final WorkflowEventSink sink;
    private final Clock clock;
    private final CheckpointStore checkpoints;
    private final WorkflowSpecValidator validator = new WorkflowSpecValidator();

    public WorkflowInterpreter(OperationRegistry registry, WorkflowEventSink sink) {
        this(registry, sink, Clock.systemUTC(), null);
    }

    public WorkflowInterpreter(OperationRegistry registry, WorkflowEventSink sink, Clock clock) {
        this(registry, sink, clock, null);
    }

    /**
     * Durable interpreter: progress commits to {@code checkpoints} (null = ephemeral).
     * In durable mode the store's journal is the authoritative event record; the sink
     * observes live events and is non-authoritative across incarnations (a resumed
     * run's sink sees only the new incarnation's events).
     */
    public WorkflowInterpreter(OperationRegistry registry, WorkflowEventSink sink, Clock clock,
            CheckpointStore checkpoints) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.checkpoints = checkpoints;
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
     * @throws ResumeRejectedException         durable mode: the run is terminal or the
     *                                         spec's canonical hash differs from the
     *                                         one pinned at first open
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
        CheckpointStore.RunState state = checkpoints == null ? null
                : checkpoints.openRun(workflowRunId, specRef(spec), CanonicalSpecHash.of(spec));
        return new Run(spec, workflowRunId, input, context, state).execute();
    }

    /** §18 portable form derived from metadata: {@code workflow://registry/<name>@<version>}. */
    static String specRef(WorkflowSpec spec) {
        String name = spec.metadata().name();
        String version = spec.metadata().version();
        return "workflow://registry/" + name + (version != null ? "@" + version : "");
    }

    /** Node execution either continues along an edge or ends the workflow. */
    private sealed interface Flow {
        /** {@code checkpoint} is the completed node's durable record; null when none applies. */
        record Continue(String nextNodeId, CheckpointStore.NodeCheckpoint checkpoint) implements Flow {
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
        private final CheckpointStore.RunState resume;
        private final List<WorkflowEvent> buffer = new ArrayList<>();
        private CheckpointStore.InFlightAttempt pendingResume;
        private AgentContextAdapter store;

        Run(WorkflowSpec spec, String workflowRunId, Object input, AgentContext context,
                CheckpointStore.RunState resume) {
            this.spec = spec;
            this.workflowRunId = workflowRunId;
            this.resume = resume;
            this.events = new WorkflowEventFactory(workflowRunId, specRef(spec), clock, null,
                    resume != null ? resume.lastEventSequence() : 0);
            this.evaluator = new BindingEvaluator(input, spec.constants());
            this.store = new AgentContextAdapter(context);
            this.pendingResume = resume != null ? resume.inFlight().orElse(null) : null;
            spec.nodes().forEach(node -> nodesById.put(node.id(), node));
        }

        WorkflowRunOutcome execute() {
            String current;
            if (resume != null && resume.lastEventSequence() > 0) {
                current = restoreCommittedState();
            } else {
                emit(events.workflowStarted(spec.metadata().name(), spec.entrypoint()));
                current = spec.entrypoint();
            }
            while (true) {
                WorkflowSpecNode node = nodesById.get(current);
                CheckpointStore.InFlightAttempt inflight = consumeInFlight(node.id());
                if (inflight == null) {
                    emit(events.nodeStarted(node.id(), nodeKind(node)));
                }
                Flow flow = switch (node) {
                    case TaskSpecNode task -> executeTask(task, inflight);
                    case DecisionSpecNode decision -> executeDecision(decision, inflight);
                    case TerminateSpecNode terminate -> executeTerminate(terminate);
                };
                if (flow instanceof Flow.End(WorkflowRunOutcome outcome)) {
                    if (checkpoints != null) {
                        List<WorkflowEvent> committed = drain();
                        checkpoints.completeRun(workflowRunId, outcome.terminalState(), committed);
                        publish(committed);
                    }
                    return outcome;
                }
                Flow.Continue next = (Flow.Continue) flow;
                if (checkpoints != null && next.checkpoint() != null) {
                    List<WorkflowEvent> committed = drain();
                    checkpoints.commitNode(workflowRunId, next.checkpoint(), committed);
                    publish(committed);
                }
                current = next.nextNodeId();
            }
        }

        // ---------------------------------------------------------------------
        // Durability plumbing
        // ---------------------------------------------------------------------

        /**
         * Ephemeral: publish at emit (the sink is the only record). Durable: buffer —
         * events reach the observer sink only AFTER their boundary commits (DESIGN
         * "commit first, publish second"): an observer must never see a phantom event
         * that a crash later replaces with a different event of the same sequence.
         */
        private void emit(WorkflowEvent event) {
            if (checkpoints == null) {
                sink.emit(event);
            } else {
                buffer.add(event);
            }
        }

        private List<WorkflowEvent> drain() {
            List<WorkflowEvent> drained = List.copyOf(buffer);
            buffer.clear();
            return drained;
        }

        /** Post-commit observer publication; a throwing observer no longer loses committed events. */
        private void publish(List<WorkflowEvent> committed) {
            committed.forEach(sink::emit);
        }

        /**
         * Silent replay of committed progress: recorded results feed bindings and
         * routing exactly as live execution would, with no re-dispatch and no event
         * re-emission (those events are already committed with their boundaries).
         * Returns the node id at which live execution resumes.
         */
        private String restoreCommittedState() {
            for (CheckpointStore.NodeCheckpoint checkpoint : resume.committedNodes()) {
                if (checkpoint.result() instanceof OperationResult.Success success) {
                    evaluator.recordOutput(checkpoint.nodeId(), success.output());
                    if (nodesById.get(checkpoint.nodeId()) instanceof DecisionSpecNode) {
                        String outcome = extractOutcome(success.output());
                        if (outcome != null) {
                            evaluator.recordDecision(checkpoint.nodeId(), outcome);
                        }
                    }
                    for (Map.Entry<String, Object> write : checkpoint.contextWrites().entrySet()) {
                        store = store.put(write.getKey(), write.getValue());
                    }
                }
            }
            if (pendingResume != null) {
                return pendingResume.nodeId();
            }
            List<CheckpointStore.NodeCheckpoint> committed = resume.committedNodes();
            if (committed.isEmpty()) {
                throw new IllegalStateException("run '" + workflowRunId + "' has committed events but "
                        + "no committed progress records — corrupt checkpoint state");
            }
            return silentRoute(committed.get(committed.size() - 1));
        }

        /** Recomputes a committed node's §16 edge selection deterministically, emitting nothing. */
        private String silentRoute(CheckpointStore.NodeCheckpoint checkpoint) {
            List<WorkflowEdgeSpec> matches;
            if (checkpoint.result() instanceof OperationResult.Success success) {
                String outcome = nodesById.get(checkpoint.nodeId()) instanceof DecisionSpecNode
                        ? extractOutcome(success.output()) : null;
                matches = successMatches(checkpoint.nodeId(), outcome);
            } else {
                matches = errorMatches(checkpoint.nodeId(), errorOf(checkpoint.result()));
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("replay of node '" + checkpoint.nodeId()
                        + "' did not select exactly one edge — corrupt checkpoint state");
            }
            return matches.get(0).to();
        }

        private CheckpointStore.InFlightAttempt consumeInFlight(String nodeId) {
            if (pendingResume != null && pendingResume.nodeId().equals(nodeId)) {
                CheckpointStore.InFlightAttempt attempt = pendingResume;
                pendingResume = null;
                return attempt;
            }
            return null;
        }

        private void commitDispatchBoundary(String nodeId, String ref, int attempt, Instant scheduledFor) {
            if (checkpoints != null) {
                List<WorkflowEvent> committed = drain();
                checkpoints.commitDispatch(workflowRunId,
                        new CheckpointStore.DispatchRecord(nodeId, ref, attempt, scheduledFor, null),
                        committed);
                publish(committed);
            }
        }

        private void commitRetryBoundary(String nodeId, String ref, int attempt, long delayMillis,
                Instant scheduledFor) {
            if (checkpoints != null) {
                List<WorkflowEvent> committed = drain();
                checkpoints.commitRetry(workflowRunId,
                        new CheckpointStore.RetryRecord(nodeId, ref, attempt, delayMillis, scheduledFor),
                        committed);
                publish(committed);
            }
        }

        private static Flow withCheckpoint(Flow flow, CheckpointStore.NodeCheckpoint checkpoint) {
            return flow instanceof Flow.Continue(String next, CheckpointStore.NodeCheckpoint ignored)
                    ? new Flow.Continue(next, checkpoint)
                    : flow;
        }

        // ---------------------------------------------------------------------
        // Node execution
        // ---------------------------------------------------------------------

        private Flow executeTask(TaskSpecNode task, CheckpointStore.InFlightAttempt inflight) {
            BindingResolution input = assembleInput(task.id(), task.input(), inflight == null);
            if (input instanceof BindingResolution.Failed(String reason)) {
                return failWorkflow(task.id(), "failed", reason);
            }
            Object inputValue = task.input() == null ? null
                    : ((BindingResolution.Resolved) input).value();

            AttemptOutcome attempt = dispatchWithRetry(task.id(), task.operation(), task.policies(),
                    inputValue, inflight);
            if (attempt.flow() != null) {
                return attempt.flow();
            }
            OperationResult result = attempt.result();
            if (!result.successful()) {
                return withCheckpoint(routeFailure(task.id(), result),
                        new CheckpointStore.NodeCheckpoint(task.id(), "failed", result, Map.of(),
                                attempt.attempts()));
            }

            Object output = ((OperationResult.Success) result).output();
            evaluator.recordOutput(task.id(), output);
            Map<String, Object> writes = new LinkedHashMap<>();
            Flow writeFailure = applyContextWrites(task, writes);
            if (writeFailure != null) {
                return writeFailure;
            }
            emit(events.nodeCompleted(task.id(), "succeeded"));
            return withCheckpoint(routeSuccess(task.id(), null),
                    new CheckpointStore.NodeCheckpoint(task.id(), "succeeded", result, writes,
                            attempt.attempts()));
        }

        private Flow executeDecision(DecisionSpecNode decision, CheckpointStore.InFlightAttempt inflight) {
            BindingResolution input = assembleInput(decision.id(), decision.input(), inflight == null);
            if (input instanceof BindingResolution.Failed(String reason)) {
                return failWorkflow(decision.id(), "failed", reason);
            }
            Object inputValue = decision.input() == null ? null
                    : ((BindingResolution.Resolved) input).value();

            AttemptOutcome attempt = dispatchWithRetry(decision.id(), decision.operation(),
                    decision.policies(), inputValue, inflight);
            if (attempt.flow() != null) {
                return attempt.flow();
            }
            OperationResult result = attempt.result();
            if (!result.successful()) {
                return withCheckpoint(routeFailure(decision.id(), result),
                        new CheckpointStore.NodeCheckpoint(decision.id(), "failed", result, Map.of(),
                                attempt.attempts()));
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
            emit(events.nodeCompleted(decision.id(), "succeeded"));
            return withCheckpoint(routeSuccess(decision.id(), outcome),
                    new CheckpointStore.NodeCheckpoint(decision.id(), "succeeded", result, Map.of(),
                            attempt.attempts()));
        }

        private Flow executeTerminate(TerminateSpecNode terminate) {
            Object result = null;
            if (terminate.result() != null) {
                BindingResolution resolution = evaluator.resolve(terminate.result(), store);
                if (resolution instanceof BindingResolution.Failed(String reason)) {
                    emit(events.bindingEvaluated(terminate.id(), "result",
                            terminate.result().from(), false, null));
                    return failWorkflow(terminate.id(), "failed", reason);
                }
                result = ((BindingResolution.Resolved) resolution).value();
                emit(events.bindingEvaluated(terminate.id(), "result",
                        terminate.result().from(), true, ValueDisclosure.metadataOnly(result)));
            }
            emit(events.nodeCompleted(terminate.id(), "succeeded"));

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
                        emit(events.bindingEvaluated(nodeId, entry.getKey(),
                                entry.getValue().from(), false, null));
                        return endWorkflow("failed", reason, result);
                    }
                    Object value = ((BindingResolution.Resolved) resolution).value();
                    emit(events.bindingEvaluated(nodeId, entry.getKey(),
                            entry.getValue().from(), true, ValueDisclosure.metadataOnly(value)));
                    outputs.put(entry.getKey(), value);
                }
            }
            Object disclosed = outputs != null ? outputs : result;
            emit(events.workflowCompleted("completed",
                    disclosed != null ? ValueDisclosure.metadataOnly(disclosed) : null));
            return new Flow.End(new WorkflowRunOutcome("completed", null, result, outputs));
        }

        // ---------------------------------------------------------------------
        // Dispatch and retry (§17)
        // ---------------------------------------------------------------------

        /**
         * Either a terminal flow (cancelled/aborted/unresolvable) or the final attempt
         * result; {@code attempts} is the last attempt number for the durable record.
         */
        private record AttemptOutcome(OperationResult result, Flow flow, int attempts) {
        }

        private AttemptOutcome dispatchWithRetry(String nodeId, String operationAlias,
                PolicyBundle nodePolicies, Object input, CheckpointStore.InFlightAttempt inflight) {
            OperationDeclaration declaration = spec.operations().get(operationAlias);
            String ref = declaration.ref();
            OperationHandler handler;
            try {
                handler = registry.resolve(ref);
            } catch (UnknownOperationException ex) {
                return new AttemptOutcome(null,
                        failWorkflow(nodeId, "aborted", "unknown operation: " + ex.operationRef()), 0);
            } catch (RuntimeException ex) {
                // a custom registry that throws must not kill the run without a terminal event
                return new AttemptOutcome(null, failWorkflow(nodeId, "aborted",
                        "operation resolution failed: " + ref), 0);
            }
            RetryPolicySpec retryPolicy = effective(nodePolicies, declaration.defaultPolicies(),
                    spec.policies(), PolicyBundle::retry);
            TimeoutPolicySpec timeoutPolicy = effective(nodePolicies, declaration.defaultPolicies(),
                    spec.policies(), PolicyBundle::timeout);

            int attempt = 1;
            Instant scheduledFor = null; // set on post-retry dispatches (ledger, D3)
            OperationResult interruptedResult = null;
            if (inflight != null) {
                if (inflight.retryScheduled()) {
                    // committed RetryScheduled (§17 resume): fire-now ONLY when the scheduled
                    // time has passed (misfire policy); a not-yet-due retry waits out its
                    // remaining delay — resume must not turn long backoffs into hot loops
                    attempt = inflight.attemptNumber() + 1;
                    scheduledFor = inflight.scheduledFor();
                    if (scheduledFor != null) {
                        long remaining = java.time.Duration.between(clock.instant(), scheduledFor)
                                .toMillis();
                        if (remaining > 0 && !sleep(remaining)) {
                            return interruptedWhileWaiting(nodeId, attempt);
                        }
                    }
                } else {
                    // dispatched-without-result: normalize through ordinary §17 precedence
                    attempt = inflight.attemptNumber();
                    interruptedResult = OperationResult.failure(new ErrorEnvelope("INTERRUPTED",
                            "attempt " + attempt + " was dispatched but its result was never committed"
                                    + " (interpreter shutdown)",
                            true, ErrorEnvelope.ORIGIN_INFRA, null));
                }
            }
            while (true) {
                OperationResult result;
                if (interruptedResult != null) {
                    result = interruptedResult;
                    interruptedResult = null;
                } else {
                    emit(events.operationDispatched(nodeId, ref, attempt, scheduledFor));
                    commitDispatchBoundary(nodeId, ref, attempt, scheduledFor);
                    result = executeAttempt(handler,
                            new OperationInvocation(workflowRunId, nodeId, ref, attempt),
                            input, timeoutPolicy);
                }

                if (result.successful()) {
                    OperationResult.Success success = (OperationResult.Success) result;
                    Object output = success.output();
                    emit(events.operationSucceeded(nodeId, ref, attempt,
                            output != null ? ValueDisclosure.metadataOnly(output) : null,
                            success.usage()));
                    return new AttemptOutcome(result, null, attempt);
                }
                if (!result.routable()) {
                    // cancelled/aborted: terminate without error-edge evaluation (§17 rules 10–11)
                    String state = result.status().wireName();
                    String reason = switch (result) {
                        case OperationResult.Cancelled c -> c.reason();
                        case OperationResult.Aborted a -> a.reason();
                        default -> state;
                    };
                    return new AttemptOutcome(null, failWorkflow(nodeId, state, reason), attempt);
                }
                emit(events.operationFailed(nodeId, ref, attempt, result));
                RetryDecision decision = RetryDecider.decide(retryPolicy, result, attempt);
                if (decision instanceof RetryDecision.Retry(long delayMillis, String reason)) {
                    emit(events.retryScheduled(nodeId, ref, attempt, reason, delayMillis, retryPolicy));
                    scheduledFor = clock.instant().plusMillis(delayMillis);
                    commitRetryBoundary(nodeId, ref, attempt, delayMillis, scheduledFor);
                    if (!sleep(delayMillis)) {
                        return interruptedWhileWaiting(nodeId, attempt);
                    }
                    attempt++;
                    continue;
                }
                return new AttemptOutcome(result, null, attempt); // exhausted → caller routes error edges
            }
        }

        /**
         * Ephemeral: the run aborts (§17 — there is nothing to resume). Durable: throw
         * without a terminal commit — the committed dispatch/retry state stays
         * resumable; a graceful shutdown must never be more destructive than a crash.
         */
        private AttemptOutcome interruptedWhileWaiting(String nodeId, int attempt) {
            if (checkpoints != null) {
                throw new WorkflowInterruptedException("interrupted while waiting to retry node '"
                        + nodeId + "' (attempt " + attempt + "); the run remains resumable");
            }
            return new AttemptOutcome(null, failWorkflow(nodeId, "aborted",
                    "interrupted while waiting to retry"), attempt);
        }

        /**
         * One attempt behind the §19 defensive boundary, with the per-attempt timeout
         * applied. On expiry the worker thread is interrupted best-effort but never
         * joined — a non-cooperative handler may keep running concurrently with the
         * next attempt (documented alpha in-memory semantics; the Operation Contract's
         * idempotency obligation covers it; the durable interpreter inherits the same
         * per-attempt supervision — its recovery answer is the INTERRUPTED
         * normalization plus operation idempotency, not thread supervision).
         */
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
                worker.interrupt();
                if (checkpoints != null) {
                    throw new WorkflowInterruptedException("interpreter interrupted during attempt "
                            + invocation.attemptNumber() + " of node '" + invocation.nodeId()
                            + "'; the run remains resumable");
                }
                return OperationResult.aborted("interpreter interrupted during attempt");
            } catch (ExecutionException ex) {
                // guarded() catches Throwable, so this is belt-and-braces only
                return unhandled(ex.getCause() != null ? ex.getCause() : ex);
            }
        }

        private OperationResult guarded(OperationHandler handler, OperationInvocation invocation,
                Object input) {
            try {
                OperationResult result = handler.execute(invocation, store.context(), input);
                return result != null ? result
                        : OperationResult.failure(new ErrorEnvelope("UNHANDLED_EXCEPTION",
                                "operation handler returned null", false, ErrorEnvelope.ORIGIN_CODE, null));
            } catch (Throwable ex) {
                // Throwable, not Exception: §19 says one misbehaving handler must not
                // bypass event emission — an AssertionError must produce the same
                // conformant stream on the timeout and non-timeout paths alike.
                return unhandled(ex);
            }
        }

        private OperationResult unhandled(Throwable ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return OperationResult.failure(new ErrorEnvelope("UNHANDLED_EXCEPTION", message, false,
                    ErrorEnvelope.ORIGIN_CODE, Map.of("exceptionClass", ex.getClass().getName())));
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
         * translate to a null dispatch input. {@code emitEvents} is false on the
         * resumed in-flight node: its evaluations were committed with the dispatch
         * boundary, and re-evaluation is silent recomputation, not a new evaluation.
         */
        private BindingResolution assembleInput(String nodeId, Map<String, Binding> declared,
                boolean emitEvents) {
            if (declared == null || declared.isEmpty()) {
                return BindingResolution.resolved(Map.of());
            }
            Map<String, Object> assembled = new LinkedHashMap<>();
            for (Map.Entry<String, Binding> entry : new TreeMap<>(declared).entrySet()) {
                BindingResolution resolution = evaluator.resolve(entry.getValue(), store);
                if (resolution instanceof BindingResolution.Failed(String reason)) {
                    if (emitEvents) {
                        emit(events.bindingEvaluated(nodeId, entry.getKey(),
                                entry.getValue().from(), false, null));
                    }
                    return BindingResolution.failed(reason);
                }
                Object value = ((BindingResolution.Resolved) resolution).value();
                if (emitEvents) {
                    emit(events.bindingEvaluated(nodeId, entry.getKey(),
                            entry.getValue().from(), true, ValueDisclosure.metadataOnly(value)));
                }
                assembled.put(entry.getKey(), value);
            }
            return BindingResolution.resolved(assembled);
        }

        /**
         * Applies contextWrites in lexicographic key order (§14); null = no failure.
         * Applied writes are collected into {@code applied} for the durable checkpoint.
         */
        private Flow applyContextWrites(TaskSpecNode task, Map<String, Object> applied) {
            if (task.contextWrites() == null) {
                return null;
            }
            for (Map.Entry<String, Binding> entry : new TreeMap<>(task.contextWrites()).entrySet()) {
                BindingResolution resolution = evaluator.resolve(entry.getValue(), store);
                if (resolution instanceof BindingResolution.Failed(String reason)) {
                    emit(events.bindingEvaluated(task.id(), entry.getKey(),
                            entry.getValue().from(), false, null));
                    return failWorkflow(task.id(), "failed", reason);
                }
                Object value = ((BindingResolution.Resolved) resolution).value();
                ValueDisclosure disclosure = ValueDisclosure.metadataOnly(value);
                emit(events.bindingEvaluated(task.id(), entry.getKey(),
                        entry.getValue().from(), true, disclosure));
                store = store.put(entry.getKey(), value);
                applied.put(entry.getKey(), value);
                emit(events.contextWriteApplied(task.id(), entry.getKey(),
                        entry.getValue().from(), disclosure));
            }
            return null;
        }

        // ---------------------------------------------------------------------
        // Edge selection (§16) and terminal transitions
        // ---------------------------------------------------------------------

        private List<WorkflowEdgeSpec> successMatches(String nodeId, String outcome) {
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
            return matches;
        }

        private List<WorkflowEdgeSpec> errorMatches(String nodeId, ErrorEnvelope error) {
            List<WorkflowEdgeSpec> matches = new ArrayList<>();
            for (WorkflowEdgeSpec edge : spec.edges()) {
                if (edge.from().equals(nodeId)
                        && edge.when() instanceof ErrorCondition(ErrorMatch match)
                        && errorMatches(match, error)) {
                    matches.add(edge);
                }
            }
            return matches;
        }

        private static ErrorEnvelope errorOf(OperationResult result) {
            return switch (result) {
                case OperationResult.Failure f -> f.error();
                case OperationResult.TimedOut t -> t.error();
                default -> throw new IllegalStateException("not routable: " + result.status());
            };
        }

        private Flow routeSuccess(String nodeId, String outcome) {
            return selectExactlyOne(nodeId, successMatches(nodeId, outcome),
                    outcome != null ? "decision_outcome_match" : "always");
        }

        private Flow routeFailure(String nodeId, OperationResult result) {
            emit(events.nodeCompleted(nodeId, "failed"));
            ErrorEnvelope error = errorOf(result);
            List<WorkflowEdgeSpec> matches = errorMatches(nodeId, error);
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
                emit(events.edgeSelected(edge, reason));
                return new Flow.Continue(edge.to(), null);
            }
            String detail = matches.isEmpty()
                    ? "no matching edge from node '" + nodeId + "'"
                    : matches.size() + " edges match from node '" + nodeId + "' (§16 requires exactly one)";
            return endWorkflow("failed", detail, null);
        }

        /**
         * Node-level termination: NodeCompleted whose state mirrors how the node ended
         * ({@code failed}, or {@code cancelled}/{@code aborted} when the run ends that
         * way mid-node — §9), then the matching workflow terminal event.
         */
        private Flow failWorkflow(String nodeId, String terminalState, String reason) {
            emit(events.nodeCompleted(nodeId, terminalState));
            return endWorkflow(terminalState, reason, null);
        }

        private Flow endWorkflow(String terminalState, String reason, Object result) {
            emit(switch (terminalState) {
                case "cancelled" -> events.workflowCancelled(reason);
                case "aborted" -> events.workflowAborted(reason);
                default -> events.workflowFailed(reason);
            });
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
