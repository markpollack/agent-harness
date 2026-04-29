package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.AgentStep;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.patterns.graph.NodeType;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The primary developer-facing entry point for composing multi-step workflows.
 * <p>
 * {@code Workflow} implements {@link Step}, enabling composition without adapters.
 *
 * @param <I> the workflow input type
 * @param <O> the workflow output type
 */
public final class Workflow<I, O> implements Step<I, O> {

    private final WorkflowGraph<I, O> graph;

    private final WorkflowExecutor executor;

    private Workflow(WorkflowGraph<I, O> graph, WorkflowExecutor executor) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
        this.executor = executor;
    }

    @Override
    public String name() {
        return graph.name();
    }

    @Override
    public O execute(AgentContext ctx, I input) {
        return resolveExecutor().execute(graph, ctx, input);
    }

    WorkflowExecutor resolveExecutor() {
        return executor != null ? executor : new WorkflowExecutor();
    }

    /**
     * Returns this workflow's executor if one was explicitly configured (via
     * {@code .withExecutor()}); otherwise returns {@code fallback}. Used by
     * {@link WorkflowExecutor} so that sub-workflow leaf steps inherit the parent's
     * {@link StepRunner} and {@link TraceRecorder} rather than defaulting to a
     * fresh {@code LocalStepRunner}.
     */
    WorkflowExecutor resolveExecutorOr(WorkflowExecutor fallback) {
        return executor != null ? executor : fallback;
    }

    public WorkflowGraph<I, O> graph() {
        return graph;
    }

    // -------------------------------------------------------------------------
    // Entry points
    // -------------------------------------------------------------------------

    public static <I, O> WorkflowBuilder<I, O> define(String name) {
        return new WorkflowBuilder<>(name);
    }

    /**
     * Power shortcut: LLM autonomously invokes sub-agents until satisfied.
     * <p>
     * Internally composed as {@code repeatUntil(pred).step(decision(client).options(agents)).end()}.
     * The LLM chooses which agent to invoke on each iteration; the loop exits
     * when the predicate passes.
     *
     * <pre>{@code
     * Workflow.supervisor("delegate", routingClient)
     *     .agents(codeReview, securityAudit, docUpdate)
     *     .until(ctx -> ctx.get(ITERATION_COUNT).orElse(0) >= 5)
     *     .run(event);
     * }</pre>
     */
    public static <I, O> SupervisorBuilder<I, O> supervisor(String name, ChatClient routingClient) {
        return new SupervisorBuilder<>(name, routingClient);
    }

    // -------------------------------------------------------------------------
    // WorkflowBuilder
    // -------------------------------------------------------------------------

    public static final class WorkflowBuilder<I, O> {

        private final String name;
        private final List<WorkflowNode> nodes = new ArrayList<>();
        private final List<WorkflowEdge> edges = new ArrayList<>();
        private String lastNodeName;
        private final AtomicInteger nodeCounter = new AtomicInteger(0);
        // Recovery nodes from onError() that need wiring to the next step
        private final List<String> pendingConvergence = new ArrayList<>();
        // Maps step name → generated node name for backTo() target resolution
        private final Map<String, String> stepNameToNodeName = new LinkedHashMap<>();

        private WorkflowExecutor executor;

        private WorkflowBuilder(String name) {
            this.name = Objects.requireNonNull(name, "name must not be null");
        }

        /**
         * Sets a custom {@link WorkflowExecutor} for this workflow.
         * <p>
         * When set, all {@code run()} and {@code build()} methods use this executor
         * instead of creating a default one. Use this to inject a configured
         * {@link StepRunner} and {@link TraceRecorder}.
         *
         * @param executor the executor to use
         * @return this builder
         */
        public WorkflowBuilder<I, O> withExecutor(WorkflowExecutor executor) {
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            return this;
        }

        private WorkflowExecutor resolveExecutor() {
            return executor != null ? executor : new WorkflowExecutor();
        }

        public WorkflowBuilder<I, O> step(Step<?, ?> step) {
            String nodeName = uniqueNodeName(step.name());
            nodes.add(new WorkflowNode.StepNode(nodeName, detectNodeType(step), step));

            if (lastNodeName != null) {
                edges.add(WorkflowEdge.sequence(lastNodeName, nodeName));
            }
            // Wire pending convergence nodes (from onError recovery) to this node
            for (String pending : pendingConvergence) {
                edges.add(WorkflowEdge.sequence(pending, nodeName));
            }
            pendingConvergence.clear();

            lastNodeName = nodeName;
            stepNameToNodeName.put(step.name(), nodeName);
            return this;
        }

        public WorkflowBuilder<I, O> then(Step<?, ?> step) {
            return step(step);
        }

        // -- Parallel (exploded: ForkNode + N StepNodes + JoinNode) --

        @SafeVarargs
        public final WorkflowBuilder<I, O> parallel(Step<?, ?>... steps) {
            if (steps.length == 0) {
                throw new IllegalArgumentException("parallel requires at least one step");
            }

            int seq = nodeCounter.incrementAndGet();
            String forkName = "fork-" + seq;
            String joinName = "join-" + seq;

            nodes.add(new WorkflowNode.ForkNode(forkName, joinName));
            if (lastNodeName != null) {
                edges.add(WorkflowEdge.sequence(lastNodeName, forkName));
            }

            for (int i = 0; i < steps.length; i++) {
                Step<?, ?> s = steps[i];
                String branchNodeName = uniqueNodeName(s.name());
                nodes.add(new WorkflowNode.StepNode(branchNodeName, detectNodeType(s), s));
                edges.add(WorkflowEdge.conditional(forkName, branchNodeName,
                        new EdgeCondition.BranchIndex(i), "branch-" + i));
                edges.add(WorkflowEdge.sequence(branchNodeName, joinName));
            }

            nodes.add(new WorkflowNode.JoinNode(joinName, WorkflowNode.JoinMode.ENRICHMENT));
            lastNodeName = joinName;
            return this;
        }

        // -- Dynamic Parallel (runtime fan-out: one step per item) --

        /**
         * Dynamic fan-out: at execution time, evaluates the supplier to get a list of items,
         * creates a step per item via the mapper, fans them all out concurrently, and collects
         * results as {@code List<Object>}.
         *
         * <pre>{@code
         * .parallel(ctx -> ctx.require(FILES_KEY), file -> analyzeStep)
         * }</pre>
         *
         * @param itemsSupplier provides the items to fan out over (evaluated at execution time)
         * @param mapper        creates a step for each item
         * @return this builder
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public WorkflowBuilder<I, O> parallel(
                java.util.function.Function<AgentContext, List<?>> itemsSupplier,
                java.util.function.Function<Object, Step<?, ?>> mapper) {
            String dynamicParName = "dynamic-parallel-" + nodeCounter.incrementAndGet();
            Step<Object, List<Object>> dynamicStep = Step.named(dynamicParName, (ctx, input) -> {
                List<?> items = itemsSupplier.apply(ctx);
                if (items == null || items.isEmpty()) {
                    return List.of();
                }
                List<java.util.concurrent.CompletableFuture<Object>> futures = new java.util.ArrayList<>();
                for (Object item : items) {
                    Step step = mapper.apply(item);
                    futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> ((Step) step).execute(ctx, item)));
                }
                return futures.stream()
                        .map(java.util.concurrent.CompletableFuture::join)
                        .collect(java.util.stream.Collectors.toList());
            });

            return step(dynamicStep);
        }

        // -- RepeatUntil (while-do: LoopEntryNode + body StepNodes + LoopExitNode) --

        public LoopBuilder<I, O> repeatUntil(Predicate<AgentContext> predicate) {
            return new LoopBuilder<>(this, predicate, null);
        }

        // -- RepeatUntilOutput (do-while: body StepNodes + LoopCheckNode + LoopExitNode) --

        public LoopBuilder<I, O> repeatUntilOutput(Predicate<Object> outputPredicate) {
            return new LoopBuilder<>(this, null, outputPredicate);
        }

        // -- Decision (exploded: DecisionNode + N StepNodes + JoinNode) --

        public DecisionBuilder<I, O> decision(ChatClient routingClient) {
            return new DecisionBuilder<>(this, routingClient);
        }

        // -- Branch (exploded: GatewayNode + 2 StepNodes + JoinNode) --

        public BranchBuilder<I, O> branch(Predicate<Object> predicate) {
            return new BranchBuilder<>(this, predicate);
        }

        // -- Gate (exploded: GateNode + pass/fail/timeout StepNodes + JoinNode) --

        public GateBuilder<I, O> gate(Gate<?> gate) {
            return new GateBuilder<>(this, gate);
        }

        // -- OnError --

        public WorkflowBuilder<I, O> onError(Class<? extends Exception> exceptionType, Step<?, ?> recoveryStep) {
            if (lastNodeName == null) {
                throw new IllegalStateException("onError requires a preceding step");
            }
            String recoveryName = uniqueNodeName(recoveryStep.name());
            nodes.add(new WorkflowNode.StepNode(recoveryName, detectNodeType(recoveryStep), recoveryStep));
            edges.add(WorkflowEdge.conditional(lastNodeName, recoveryName,
                    new EdgeCondition.ErrorMatch(exceptionType),
                    "error:" + exceptionType.getName()));
            pendingConvergence.add(recoveryName);
            return this;
        }

        // -- BackTo (cyclic back-edge to an earlier step) --

        /**
         * Adds a conditional back-edge from the current node to a previously declared step.
         * When the condition evaluates to {@code true} on the current output, execution
         * jumps back to the target step. When {@code false}, execution continues forward
         * to the next step in the chain.
         * <p>
         * Use for retry patterns: {@code step(rebase).step(runTests).backTo("rebase", testsFailed).step(merge)}.
         * <p>
         * {@link RunOptions#maxIterations()} acts as a circuit breaker for cycles.
         *
         * @param stepName  the {@link Step#name()} of a previously added step
         * @param condition evaluated on current output; {@code true} = take the back-edge
         * @return this builder (lastNodeName unchanged — next step chains from the same node)
         * @throws IllegalStateException    if no preceding step exists
         * @throws IllegalArgumentException if stepName was not previously declared
         */
        public WorkflowBuilder<I, O> backTo(String stepName, Predicate<Object> condition) {
            if (lastNodeName == null) {
                throw new IllegalStateException("backTo requires a preceding step");
            }
            String targetNodeName = stepNameToNodeName.get(stepName);
            if (targetNodeName == null) {
                throw new IllegalArgumentException("Unknown step name for backTo: '" + stepName
                        + "'. Declared steps: " + stepNameToNodeName.keySet());
            }
            edges.add(WorkflowEdge.conditional(lastNodeName, targetNodeName,
                    new EdgeCondition.BackEdge(condition), "backTo:" + stepName));
            // Do NOT advance lastNodeName — next step() chains from the same node
            return this;
        }

        // -- Compile / Run / Build --

        public WorkflowGraph<I, O> compile() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("Workflow '" + name + "' has no steps");
            }
            String startNode = nodes.get(0).name();
            String finishNode = lastNodeName != null ? lastNodeName : startNode;
            return WorkflowGraph.of(name, List.copyOf(nodes), List.copyOf(edges), startNode, finishNode);
        }

        public O run(I input) {
            WorkflowGraph<I, O> graph = compile();
            return resolveExecutor().execute(graph, AgentContext.create(), input);
        }

        public O run(I input, RunOptions options) {
            WorkflowGraph<I, O> graph = compile();
            return resolveExecutor().execute(graph, AgentContext.create(), input, options);
        }

        /**
         * Runs this workflow with a parent context, inheriting all entries but creating
         * a fresh {@code WORKFLOW_RUN_ID} and setting {@code WORKFLOW_NAME} to this workflow's name.
         *
         * @param input the workflow input
         * @param ctx   the parent context to inherit from
         * @return the workflow output
         */
        public O run(I input, AgentContext ctx) {
            WorkflowGraph<I, O> graph = compile();
            AgentContext subCtx = ctx.mutate()
                    .with(AgentContext.WORKFLOW_NAME, name)
                    .with(AgentContext.WORKFLOW_RUN_ID, UUID.randomUUID().toString())
                    .build();
            return resolveExecutor().execute(graph, subCtx, input);
        }

        /**
         * Runs this workflow with a parent context and run options.
         *
         * @param input   the workflow input
         * @param ctx     the parent context to inherit from
         * @param options runtime constraints (max cost, max iterations, max duration)
         * @return the workflow output
         */
        public O run(I input, AgentContext ctx, RunOptions options) {
            WorkflowGraph<I, O> graph = compile();
            AgentContext subCtx = ctx.mutate()
                    .with(AgentContext.WORKFLOW_NAME, name)
                    .with(AgentContext.WORKFLOW_RUN_ID, UUID.randomUUID().toString())
                    .build();
            return resolveExecutor().execute(graph, subCtx, input, options);
        }

        public Workflow<I, O> build() {
            return new Workflow<>(compile(), executor);
        }

        // -- Internal helpers --

        String uniqueNodeName(String baseName) {
            return baseName + "-" + nodeCounter.incrementAndGet();
        }

        NodeType detectNodeType(Step<?, ?> step) {
            return (step instanceof AgentStep) ? NodeType.AGENT : NodeType.DETERMINISTIC;
        }

        void addNode(WorkflowNode node) { nodes.add(node); }
        void addEdge(WorkflowEdge edge) { edges.add(edge); }
        String lastNodeName() { return lastNodeName; }
        void setLastNodeName(String name) { this.lastNodeName = name; }
        AtomicInteger nodeCounter() { return nodeCounter; }
    }

    // -------------------------------------------------------------------------
    // LoopBuilder
    // -------------------------------------------------------------------------

    public static final class LoopBuilder<I, O> {

        private final WorkflowBuilder<I, O> parent;
        private final Predicate<AgentContext> contextExitCondition;  // while-do
        private final Predicate<Object> outputExitCondition;         // do-while
        private final List<Step<?, ?>> bodySteps = new ArrayList<>();

        private LoopBuilder(WorkflowBuilder<I, O> parent,
                            Predicate<AgentContext> contextExitCondition,
                            Predicate<Object> outputExitCondition) {
            this.parent = parent;
            this.contextExitCondition = contextExitCondition;
            this.outputExitCondition = outputExitCondition;
        }

        public LoopBuilder<I, O> step(Step<?, ?> step) {
            bodySteps.add(step);
            return this;
        }

        public LoopBuilder<I, O> then(Step<?, ?> step) {
            return step(step);
        }

        public WorkflowBuilder<I, O> end() {
            if (bodySteps.isEmpty()) {
                throw new IllegalStateException("Loop body requires at least one step");
            }

            int seq = parent.nodeCounter().incrementAndGet();

            if (contextExitCondition != null) {
                // While-do: LoopEntryNode → body → back to entry; entry → exit on done
                return buildWhileDo(seq);
            } else {
                // Do-while: body → LoopCheckNode → back to body; check → exit on done
                return buildDoWhile(seq);
            }
        }

        private WorkflowBuilder<I, O> buildWhileDo(int seq) {
            String entryName = "loop-entry-" + seq;
            String exitName = "loop-exit-" + seq;

            parent.addNode(new WorkflowNode.LoopEntryNode(entryName, contextExitCondition));
            if (parent.lastNodeName() != null) {
                parent.addEdge(WorkflowEdge.sequence(parent.lastNodeName(), entryName));
            }

            // Body steps
            String prevName = entryName;
            String firstBodyName = null;
            String lastBodyName = null;
            for (Step<?, ?> bodyStep : bodySteps) {
                String bodyNodeName = parent.uniqueNodeName(bodyStep.name());
                parent.addNode(new WorkflowNode.StepNode(bodyNodeName,
                        parent.detectNodeType(bodyStep), bodyStep));
                if (prevName.equals(entryName)) {
                    // Entry → first body (LoopContinue)
                    parent.addEdge(WorkflowEdge.conditional(entryName, bodyNodeName,
                            new EdgeCondition.LoopContinue(), "continue"));
                    firstBodyName = bodyNodeName;
                } else {
                    parent.addEdge(WorkflowEdge.sequence(prevName, bodyNodeName));
                }
                prevName = bodyNodeName;
                lastBodyName = bodyNodeName;
            }

            // Back-edge: last body → entry
            parent.addEdge(WorkflowEdge.sequence(lastBodyName, entryName));

            // Exit edge: entry → exit (LoopExit)
            parent.addNode(new WorkflowNode.LoopExitNode(exitName));
            parent.addEdge(WorkflowEdge.conditional(entryName, exitName,
                    new EdgeCondition.LoopExit(), "exit"));

            parent.setLastNodeName(exitName);
            return parent;
        }

        private WorkflowBuilder<I, O> buildDoWhile(int seq) {
            String checkName = "loop-check-" + seq;
            String exitName = "loop-exit-" + seq;

            // Body steps
            String firstBodyName = null;
            String prevName = null;
            String lastBodyName = null;
            for (Step<?, ?> bodyStep : bodySteps) {
                String bodyNodeName = parent.uniqueNodeName(bodyStep.name());
                parent.addNode(new WorkflowNode.StepNode(bodyNodeName,
                        parent.detectNodeType(bodyStep), bodyStep));
                if (firstBodyName == null) {
                    firstBodyName = bodyNodeName;
                    if (parent.lastNodeName() != null) {
                        parent.addEdge(WorkflowEdge.sequence(parent.lastNodeName(), bodyNodeName));
                    }
                } else {
                    parent.addEdge(WorkflowEdge.sequence(prevName, bodyNodeName));
                }
                prevName = bodyNodeName;
                lastBodyName = bodyNodeName;
            }

            // Check node after body
            parent.addNode(new WorkflowNode.LoopCheckNode(checkName, outputExitCondition));
            parent.addEdge(WorkflowEdge.sequence(lastBodyName, checkName));

            // Back-edge: check → first body (LoopContinue)
            parent.addEdge(WorkflowEdge.conditional(checkName, firstBodyName,
                    new EdgeCondition.LoopContinue(), "continue"));

            // Exit edge: check → exit (LoopExit)
            parent.addNode(new WorkflowNode.LoopExitNode(exitName));
            parent.addEdge(WorkflowEdge.conditional(checkName, exitName,
                    new EdgeCondition.LoopExit(), "exit"));

            parent.setLastNodeName(exitName);
            return parent;
        }
    }

    // -------------------------------------------------------------------------
    // BranchBuilder (exploded: GatewayNode + 2 StepNodes + JoinNode)
    // -------------------------------------------------------------------------

    public static final class BranchBuilder<I, O> {

        private final WorkflowBuilder<I, O> parent;
        private final Predicate<Object> predicate;
        private Step<?, ?> thenStep;

        private BranchBuilder(WorkflowBuilder<I, O> parent, Predicate<Object> predicate) {
            this.parent = parent;
            this.predicate = predicate;
        }

        public BranchBuilder<I, O> then(Step<?, ?> step) {
            this.thenStep = step;
            return this;
        }

        public WorkflowBuilder<I, O> otherwise(Step<?, ?> step) {
            if (thenStep == null) {
                throw new IllegalStateException("branch().then() must be called before otherwise()");
            }

            int seq = parent.nodeCounter().incrementAndGet();
            String gwName = "gateway-" + seq;
            String thenName = parent.uniqueNodeName(thenStep.name());
            String elseName = parent.uniqueNodeName(step.name());
            String joinName = "join-" + seq;

            parent.addNode(new WorkflowNode.GatewayNode(gwName, predicate, joinName));
            if (parent.lastNodeName() != null) {
                parent.addEdge(WorkflowEdge.sequence(parent.lastNodeName(), gwName));
            }

            // Then branch
            parent.addNode(new WorkflowNode.StepNode(thenName,
                    parent.detectNodeType(thenStep), thenStep));
            parent.addEdge(WorkflowEdge.conditional(gwName, thenName,
                    new EdgeCondition.BooleanGuard(true), "true"));
            parent.addEdge(WorkflowEdge.sequence(thenName, joinName));

            // Otherwise branch
            parent.addNode(new WorkflowNode.StepNode(elseName,
                    parent.detectNodeType(step), step));
            parent.addEdge(WorkflowEdge.conditional(gwName, elseName,
                    new EdgeCondition.BooleanGuard(false), "false"));
            parent.addEdge(WorkflowEdge.sequence(elseName, joinName));

            parent.addNode(new WorkflowNode.JoinNode(joinName, WorkflowNode.JoinMode.ENRICHMENT));
            parent.setLastNodeName(joinName);
            return parent;
        }
    }

    // -------------------------------------------------------------------------
    // DecisionBuilder (exploded: DecisionNode + N StepNodes + JoinNode)
    // -------------------------------------------------------------------------

    public static final class DecisionBuilder<I, O> {

        private final WorkflowBuilder<I, O> parent;
        private final ChatClient routingClient;
        private final Map<String, Step<?, ?>> options = new LinkedHashMap<>();

        private DecisionBuilder(WorkflowBuilder<I, O> parent, ChatClient routingClient) {
            this.parent = parent;
            this.routingClient = Objects.requireNonNull(routingClient);
        }

        public DecisionBuilder<I, O> option(String name, Step<?, ?> step) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(step);
            if (options.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate decision option name: '" + name + "'");
            }
            options.put(name, step);
            return this;
        }

        public WorkflowBuilder<I, O> end() {
            if (options.isEmpty()) {
                throw new IllegalStateException("decision() requires at least one .option()");
            }

            int seq = parent.nodeCounter().incrementAndGet();
            String decisionName = "decision-" + seq;
            String joinName = "join-" + seq;

            DecisionStep routingStep = new DecisionStep(
                    decisionName, routingClient,
                    new LinkedHashSet<>(options.keySet()),
                    DecisionStep.DEFAULT_PROMPT_TEMPLATE);

            parent.addNode(new WorkflowNode.DecisionNode(decisionName, routingStep, joinName));
            if (parent.lastNodeName() != null) {
                parent.addEdge(WorkflowEdge.sequence(parent.lastNodeName(), decisionName));
            }

            for (Map.Entry<String, Step<?, ?>> entry : options.entrySet()) {
                String optionLabel = entry.getKey();
                Step<?, ?> optionStep = entry.getValue();
                String optionNodeName = parent.uniqueNodeName(optionStep.name());
                parent.addNode(new WorkflowNode.StepNode(optionNodeName,
                        parent.detectNodeType(optionStep), optionStep));
                parent.addEdge(WorkflowEdge.conditional(decisionName, optionNodeName,
                        new EdgeCondition.OptionMatch(optionLabel), optionLabel));
                parent.addEdge(WorkflowEdge.sequence(optionNodeName, joinName));
            }

            parent.addNode(new WorkflowNode.JoinNode(joinName, WorkflowNode.JoinMode.ENRICHMENT));
            parent.setLastNodeName(joinName);
            return parent;
        }
    }

    // -------------------------------------------------------------------------
    // GateBuilder (exploded: GateNode + pass/fail/timeout StepNodes + JoinNode)
    // -------------------------------------------------------------------------

    public static final class GateBuilder<I, O> {

        private final WorkflowBuilder<I, O> parent;
        private final Gate<?> gate;
        private Step<?, ?> onPassStep;
        private Step<?, ?> onFailStep;
        private Step<?, ?> onTimeoutStep;
        private Step<?, ?> reflector;
        private int maxRetries = 0;

        private GateBuilder(WorkflowBuilder<I, O> parent, Gate<?> gate) {
            this.parent = parent;
            this.gate = Objects.requireNonNull(gate);
        }

        public GateBuilder<I, O> onPass(Step<?, ?> step) {
            this.onPassStep = Objects.requireNonNull(step);
            return this;
        }

        public GateBuilder<I, O> onFail(Step<?, ?> step) {
            this.onFailStep = Objects.requireNonNull(step);
            return this;
        }

        public GateBuilder<I, O> onTimeout(Step<?, ?> step) {
            this.onTimeoutStep = Objects.requireNonNull(step);
            return this;
        }

        /**
         * Optional reflector step that transforms a judge Verdict into constructive
         * feedback text. On gate FAIL, the executor runs the reflector and writes
         * the result to {@code AgentContext.JUDGE_REFLECTION}.
         */
        public GateBuilder<I, O> withReflector(Step<?, ?> reflector) {
            this.reflector = Objects.requireNonNull(reflector);
            return this;
        }

        /**
         * Hard cap on FAIL→retry cycles. When maxRetries > 0 and the gate returns FAIL,
         * the executor re-runs the fail step and re-evaluates the gate, up to maxRetries
         * times. If still FAIL after all retries, routes to the fail path.
         */
        public GateBuilder<I, O> maxRetries(int maxRetries) {
            if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
            this.maxRetries = maxRetries;
            return this;
        }

        public WorkflowBuilder<I, O> end() {
            if (onPassStep == null) {
                throw new IllegalStateException("gate() requires .onPass()");
            }

            int seq = parent.nodeCounter().incrementAndGet();
            String gateName = "gate-" + seq;
            String joinName = "join-" + seq;

            parent.addNode(new WorkflowNode.GateNode(gateName, gate, joinName,
                    reflector, maxRetries));
            if (parent.lastNodeName() != null) {
                parent.addEdge(WorkflowEdge.sequence(parent.lastNodeName(), gateName));
            }

            // PASS path (required)
            String passName = parent.uniqueNodeName(onPassStep.name());
            parent.addNode(new WorkflowNode.StepNode(passName,
                    parent.detectNodeType(onPassStep), onPassStep));
            parent.addEdge(WorkflowEdge.conditional(gateName, passName,
                    new EdgeCondition.GateMatch(GateDecision.PASS), "pass"));
            parent.addEdge(WorkflowEdge.sequence(passName, joinName));

            // FAIL path (optional)
            if (onFailStep != null) {
                String failName = parent.uniqueNodeName(onFailStep.name());
                parent.addNode(new WorkflowNode.StepNode(failName,
                        parent.detectNodeType(onFailStep), onFailStep));
                parent.addEdge(WorkflowEdge.conditional(gateName, failName,
                        new EdgeCondition.GateMatch(GateDecision.FAIL), "fail"));
                parent.addEdge(WorkflowEdge.sequence(failName, joinName));
            }

            // TIMEOUT path (optional)
            if (onTimeoutStep != null) {
                String timeoutName = parent.uniqueNodeName(onTimeoutStep.name());
                parent.addNode(new WorkflowNode.StepNode(timeoutName,
                        parent.detectNodeType(onTimeoutStep), onTimeoutStep));
                parent.addEdge(WorkflowEdge.conditional(gateName, timeoutName,
                        new EdgeCondition.GateMatch(GateDecision.TIMEOUT), "timeout"));
                parent.addEdge(WorkflowEdge.sequence(timeoutName, joinName));
            }

            parent.addNode(new WorkflowNode.JoinNode(joinName, WorkflowNode.JoinMode.ENRICHMENT));
            parent.setLastNodeName(joinName);
            return parent;
        }
    }

    // -------------------------------------------------------------------------
    // SupervisorBuilder (composed: loop + decision)
    // -------------------------------------------------------------------------

    public static final class SupervisorBuilder<I, O> {

        private final String name;
        private final ChatClient routingClient;
        private final List<Step<?, ?>> agents = new ArrayList<>();
        private Predicate<AgentContext> exitCondition;
        private int maxIterations = 10;

        private WorkflowExecutor executor;

        private SupervisorBuilder(String name, ChatClient routingClient) {
            this.name = Objects.requireNonNull(name);
            this.routingClient = Objects.requireNonNull(routingClient);
        }

        /**
         * Sets a custom {@link WorkflowExecutor} for this supervisor.
         *
         * @param executor the executor to use
         * @return this builder
         */
        public SupervisorBuilder<I, O> withExecutor(WorkflowExecutor executor) {
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            return this;
        }

        private WorkflowExecutor resolveExecutor() {
            return executor != null ? executor : new WorkflowExecutor();
        }

        @SafeVarargs
        public final SupervisorBuilder<I, O> agents(Step<?, ?>... agents) {
            for (Step<?, ?> agent : agents) {
                this.agents.add(Objects.requireNonNull(agent));
            }
            return this;
        }

        public SupervisorBuilder<I, O> until(Predicate<AgentContext> exitCondition) {
            this.exitCondition = Objects.requireNonNull(exitCondition);
            return this;
        }

        public SupervisorBuilder<I, O> maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public O run(I input) {
            return build().execute(AgentContext.create(), input);
        }

        public O run(I input, RunOptions options) {
            WorkflowGraph<I, O> graph = build().graph();
            return resolveExecutor().execute(graph, AgentContext.create(), input, options);
        }

        /**
         * Runs this supervisor with a parent context, inheriting all entries but creating
         * a fresh {@code WORKFLOW_RUN_ID} and setting {@code WORKFLOW_NAME}.
         *
         * @param input the input
         * @param ctx   the parent context to inherit from
         * @return the output
         */
        public O run(I input, AgentContext ctx) {
            WorkflowGraph<I, O> graph = build().graph();
            AgentContext subCtx = ctx.mutate()
                    .with(AgentContext.WORKFLOW_NAME, name)
                    .with(AgentContext.WORKFLOW_RUN_ID, UUID.randomUUID().toString())
                    .build();
            return resolveExecutor().execute(graph, subCtx, input);
        }

        /**
         * Runs this supervisor with a parent context and run options.
         *
         * @param input   the input
         * @param ctx     the parent context to inherit from
         * @param options runtime constraints
         * @return the output
         */
        public O run(I input, AgentContext ctx, RunOptions options) {
            WorkflowGraph<I, O> graph = build().graph();
            AgentContext subCtx = ctx.mutate()
                    .with(AgentContext.WORKFLOW_NAME, name)
                    .with(AgentContext.WORKFLOW_RUN_ID, UUID.randomUUID().toString())
                    .build();
            return resolveExecutor().execute(graph, subCtx, input, options);
        }

        public Workflow<I, O> build() {
            if (agents.isEmpty()) {
                throw new IllegalStateException("supervisor() requires at least one agent");
            }
            if (exitCondition == null) {
                exitCondition = ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= maxIterations;
            }

            // Build a decision sub-workflow as a composable Step
            WorkflowBuilder<Object, Object> decWb = Workflow.define(name + "-decision");
            DecisionBuilder<Object, Object> db = decWb.decision(routingClient);
            for (Step<?, ?> agent : agents) {
                db.option(agent.name(), agent);
            }
            db.end();
            Workflow<Object, Object> decisionWorkflow = decWb.build();

            // Compose: repeatUntil(exitCondition).step(decisionWorkflow).end()
            WorkflowBuilder<I, O> wb = Workflow.define(name);
            wb.repeatUntil(exitCondition)
                    .step(decisionWorkflow)
                    .end();

            return wb.build();
        }
    }
}
