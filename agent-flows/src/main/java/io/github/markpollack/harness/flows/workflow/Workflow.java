/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.harness.flows.workflow;

import io.github.markpollack.harness.flows.AgentContext;
import io.github.markpollack.harness.flows.Step;
import io.github.markpollack.harness.patterns.graph.NodeType;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The primary developer-facing entry point for composing multi-step workflows.
 * <p>
 * {@code Workflow} provides two surfaces:
 * <ol>
 *   <li><strong>Named factory shortcuts</strong> for common cases — communicate intent from the first word</li>
 *   <li><strong>Full fluent DSL</strong> via {@link #define(String)} for complex flows with gates, branches, and error paths</li>
 * </ol>
 * <p>
 * {@code Workflow} implements {@link Step}, enabling composition without adapters:
 * a workflow can be used as a step inside another workflow or in
 * {@code AgentFlow.parallel()}.
 *
 * <pre>{@code
 * // Simple sequential
 * Workflow.define("pr-review")
 *     .step(fetchDiff)
 *     .then(analyzeDiff)
 *     .then(postComment)
 *     .run(event);
 *
 * // With branch
 * Workflow.define("review")
 *     .step(analyze)
 *     .branch(output -> isHighRisk(output))
 *         .then(detailedReview)
 *         .otherwise(quickReview)
 *     .run(input);
 * }</pre>
 *
 * @param <I> the workflow input type (first step's input)
 * @param <O> the workflow output type (last step's output)
 */
public final class Workflow<I, O> implements Step<I, O> {

    private final WorkflowGraph<I, O> graph;

    private Workflow(WorkflowGraph<I, O> graph) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
    }

    @Override
    public String name() {
        return graph.name();
    }

    @Override
    public O execute(AgentContext ctx, I input) {
        return new WorkflowExecutor().execute(graph, ctx, input);
    }

    /**
     * Returns the compiled workflow graph (the IR).
     *
     * @return the workflow graph
     */
    public WorkflowGraph<I, O> graph() {
        return graph;
    }

    // -------------------------------------------------------------------------
    // Entry points
    // -------------------------------------------------------------------------

    /**
     * Starts building a workflow with the given name.
     * <p>
     * The name appears in traces, errors, and visualization.
     *
     * @param name the workflow identifier
     * @param <I>  input type
     * @param <O>  output type
     * @return a new WorkflowBuilder
     */
    public static <I, O> WorkflowBuilder<I, O> define(String name) {
        return new WorkflowBuilder<>(name);
    }

    // -------------------------------------------------------------------------
    // WorkflowBuilder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for constructing workflows.
     * <p>
     * The builder only builds a {@link WorkflowGraph} — it never executes.
     * {@link #run(Object)} is the only execution point.
     *
     * @param <I> input type
     * @param <O> output type
     */
    public static final class WorkflowBuilder<I, O> {

        private final String name;
        private final List<WorkflowNode> nodes = new ArrayList<>();
        private final List<WorkflowEdge> edges = new ArrayList<>();
        private String lastNodeName;
        private final AtomicInteger nodeCounter = new AtomicInteger(0);

        private WorkflowBuilder(String name) {
            this.name = Objects.requireNonNull(name, "name must not be null");
        }

        /**
         * Appends a step to the workflow.
         *
         * @param step the step to add
         * @return this builder
         */
        public WorkflowBuilder<I, O> step(Step<?, ?> step) {
            String nodeName = uniqueNodeName(step.name());
            nodes.add(new WorkflowNode(nodeName, detectNodeType(step), step));
            if (lastNodeName != null) {
                edges.add(WorkflowEdge.of(lastNodeName, nodeName));
            }
            lastNodeName = nodeName;
            return this;
        }

        /**
         * Readability alias for {@link #step(Step)}.
         *
         * @param step the step to add
         * @return this builder
         */
        public WorkflowBuilder<I, O> then(Step<?, ?> step) {
            return step(step);
        }

        /**
         * Adds concurrent fan-out steps with implicit AND join.
         * <p>
         * All steps receive the same input (the output of the previous step).
         * Results are collected into a {@code List<?>} and passed to the next step.
         *
         * @param steps the steps to run in parallel
         * @return this builder
         */
        @SafeVarargs
        public final WorkflowBuilder<I, O> parallel(Step<?, ?>... steps) {
            if (steps.length == 0) {
                throw new IllegalArgumentException("parallel requires at least one step");
            }

            // Create a synthetic parallel step that fans out and joins
            String parallelName = "parallel-" + nodeCounter.incrementAndGet();
            Step<Object, List<Object>> parallelStep = Step.named(parallelName, (ctx, input) -> {
                List<CompletableFuture<Object>> futures = new ArrayList<>();
                for (Step<?, ?> s : steps) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Step<Object, Object> typedStep = (Step) s;
                    futures.add(CompletableFuture.supplyAsync(() -> typedStep.execute(ctx, input)));
                }
                return futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList());
            });

            String nodeName = parallelName;
            nodes.add(new WorkflowNode(nodeName, NodeType.DETERMINISTIC, parallelStep));
            if (lastNodeName != null) {
                edges.add(WorkflowEdge.of(lastNodeName, nodeName));
            }
            lastNodeName = nodeName;
            return this;
        }

        /**
         * Opens a repeat-until loop.
         * <p>
         * The predicate operates on the {@link AgentContext}, not the step output.
         * This gives access to iteration count, accumulated cost, and other framework
         * metadata — which is what loop termination typically depends on.
         * <p>
         * Compare with {@link #branch(Predicate)} which operates on the step output,
         * because branch routing depends on what the previous step produced.
         *
         * @param predicate the loop termination condition (returns true to exit)
         * @return a LoopBuilder for adding the loop body
         */
        public LoopBuilder<I, O> repeatUntil(Predicate<AgentContext> predicate) {
            return new LoopBuilder<>(this, predicate);
        }

        /**
         * Opens an LLM-driven routing decision.
         * <p>
         * The routing LLM receives the current input and a list of named options,
         * then chooses one option name. The chosen option's step is executed.
         * <p>
         * Use when the next step should be chosen by the agent at runtime, not by
         * a deterministic predicate. For deterministic routing, prefer {@link #branch(Predicate)}.
         *
         * <pre>{@code
         * .step(analyzeIssue)
         * .decision(routingClient)
         *     .option("fix-code", applyFix)
         *     .option("add-test", addTest)
         *     .option("escalate", notifyHuman)
         * .end()
         * }</pre>
         *
         * @param routingClient the ChatClient used to choose the option
         * @return a DecisionBuilder for registering named options
         */
        public DecisionBuilder<I, O> decision(ChatClient routingClient) {
            return new DecisionBuilder<>(this, routingClient);
        }

        /**
         * Opens a deterministic conditional branch.
         * <p>
         * The predicate operates on the output of the previous step (as {@code Object}).
         * Use explicit parameter types in the lambda for clarity:
         * <pre>{@code
         * .branch(output -> ((ReviewReport) output).isHighRisk())
         * }</pre>
         * <p>
         * Compare with {@link #repeatUntil(Predicate)} which operates on
         * {@link AgentContext}, because loop termination depends on framework
         * metadata (iteration count, cost), not step output.
         *
         * @param predicate the branch condition (true = then branch, false = otherwise)
         * @return a BranchBuilder for specifying then/otherwise steps
         */
        public BranchBuilder<I, O> branch(Predicate<Object> predicate) {
            return new BranchBuilder<>(this, predicate);
        }

        /**
         * Attaches an error handler to the most recently added step.
         * <p>
         * When the step throws an exception matching {@code exceptionType},
         * execution routes to {@code recoveryStep} instead of propagating.
         * Error edges are visible in traces and Markov analysis.
         *
         * <pre>{@code
         * .step(callApi)
         *     .onError(TimeoutException.class, retryWithBackoff)
         *     .onError(RateLimitException.class, waitAndRetry)
         * }</pre>
         *
         * @param exceptionType the exception class to catch
         * @param recoveryStep  the step to route to on error
         * @return this builder
         */
        public WorkflowBuilder<I, O> onError(Class<? extends Exception> exceptionType, Step<?, ?> recoveryStep) {
            if (lastNodeName == null) {
                throw new IllegalStateException("onError requires a preceding step");
            }
            String recoveryName = uniqueNodeName(recoveryStep.name());
            nodes.add(new WorkflowNode(recoveryName, detectNodeType(recoveryStep), recoveryStep));
            edges.add(new WorkflowEdge(
                    lastNodeName, recoveryName, null, null,
                    "error:" + exceptionType.getName()
            ));
            return this;
        }

        /**
         * Compiles the workflow definition into a {@link WorkflowGraph}.
         * <p>
         * No execution occurs — the graph is a pure data structure.
         *
         * @return the compiled workflow graph
         */
        public WorkflowGraph<I, O> compile() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("Workflow '" + name + "' has no steps");
            }
            String startNode = nodes.get(0).name();
            String finishNode = nodes.get(nodes.size() - 1).name();
            return WorkflowGraph.of(name, List.copyOf(nodes), List.copyOf(edges), startNode, finishNode);
        }

        /**
         * Compiles and executes the workflow with the given input.
         *
         * @param input the workflow input
         * @return the workflow output
         */
        public O run(I input) {
            WorkflowGraph<I, O> graph = compile();
            return new WorkflowExecutor().execute(graph, AgentContext.create(), input);
        }

        /**
         * Compiles and executes the workflow with the given input and constraints.
         *
         * @param input   the workflow input
         * @param options runtime constraints (cost cap, iteration cap, duration cap)
         * @return the workflow output
         */
        public O run(I input, RunOptions options) {
            WorkflowGraph<I, O> graph = compile();
            return new WorkflowExecutor().execute(graph, AgentContext.create(), input, options);
        }

        /**
         * Compiles and returns a {@link Workflow} that implements {@link Step}.
         * <p>
         * Use when you need the workflow as a composable Step:
         * <pre>{@code
         * Workflow<String, String> subWorkflow = Workflow.define("sub").step(a).build();
         * Workflow.define("outer").step(subWorkflow).run(input);
         * }</pre>
         *
         * @return a Workflow implementing Step
         */
        public Workflow<I, O> build() {
            return new Workflow<>(compile());
        }

        // Internal helpers

        private String uniqueNodeName(String baseName) {
            return baseName + "-" + nodeCounter.incrementAndGet();
        }

        private NodeType detectNodeType(Step<?, ?> step) {
            // ClaudeStep and ChatClientStep are AGENT type; everything else is DETERMINISTIC
            String className = step.getClass().getSimpleName();
            if (className.contains("ClaudeStep") || className.contains("ChatClientStep")
                    || className.contains("AgentClientStep")) {
                return NodeType.AGENT;
            }
            return NodeType.DETERMINISTIC;
        }

        // Package-private for sub-builders
        void addNode(WorkflowNode node) {
            nodes.add(node);
        }

        void addEdge(WorkflowEdge edge) {
            edges.add(edge);
        }

        String lastNodeName() {
            return lastNodeName;
        }

        void setLastNodeName(String name) {
            this.lastNodeName = name;
        }

        String nextNodeName(String baseName) {
            return uniqueNodeName(baseName);
        }

        NodeType detectType(Step<?, ?> step) {
            return detectNodeType(step);
        }
    }

    // -------------------------------------------------------------------------
    // LoopBuilder
    // -------------------------------------------------------------------------

    /**
     * Builder for repeat-until loops within a workflow.
     *
     * @param <I> workflow input type
     * @param <O> workflow output type
     */
    public static final class LoopBuilder<I, O> {

        private final WorkflowBuilder<I, O> parent;
        private final Predicate<AgentContext> exitCondition;
        private final List<Step<?, ?>> bodySteps = new ArrayList<>();

        private LoopBuilder(WorkflowBuilder<I, O> parent, Predicate<AgentContext> exitCondition) {
            this.parent = parent;
            this.exitCondition = exitCondition;
        }

        /**
         * Adds a step to the loop body.
         *
         * @param step the step to repeat
         * @return this loop builder
         */
        public LoopBuilder<I, O> step(Step<?, ?> step) {
            bodySteps.add(step);
            return this;
        }

        /**
         * Closes the loop and returns to the outer workflow builder.
         *
         * @return the parent workflow builder
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public WorkflowBuilder<I, O> end() {
            if (bodySteps.isEmpty()) {
                throw new IllegalStateException("Loop body requires at least one step");
            }

            // Create a synthetic loop step
            String loopName = "loop-" + parent.nodeCounter.incrementAndGet();
            Step<Object, Object> loopStep = Step.named(loopName, (ctx, input) -> {
                Object current = input;
                int iteration = 0;
                while (true) {
                    AgentContext iterCtx = ctx.mutate()
                            .with(AgentContext.ITERATION_COUNT, iteration)
                            .build();
                    if (exitCondition.test(iterCtx)) {
                        return current;
                    }
                    for (Step bodyStep : bodySteps) {
                        current = bodyStep.execute(iterCtx, current);
                    }
                    iteration++;
                    if (iteration > 1000) {
                        throw new IllegalStateException("Loop '" + loopName + "' exceeded 1000 iterations");
                    }
                }
            });

            WorkflowNode node = new WorkflowNode(loopName, NodeType.DETERMINISTIC, loopStep);
            parent.addNode(node);
            if (parent.lastNodeName() != null) {
                parent.addEdge(WorkflowEdge.of(parent.lastNodeName(), loopName));
            }
            parent.setLastNodeName(loopName);
            return parent;
        }
    }

    // -------------------------------------------------------------------------
    // BranchBuilder
    // -------------------------------------------------------------------------

    /**
     * Builder for deterministic conditional branches.
     *
     * @param <I> workflow input type
     * @param <O> workflow output type
     */
    public static final class BranchBuilder<I, O> {

        private final WorkflowBuilder<I, O> parent;
        private final Predicate<Object> predicate;
        private Step<?, ?> thenStep;

        private BranchBuilder(WorkflowBuilder<I, O> parent, Predicate<Object> predicate) {
            this.parent = parent;
            this.predicate = predicate;
        }

        /**
         * Specifies the step to execute when the condition is true.
         *
         * @param step the true-branch step
         * @return this branch builder
         */
        public BranchBuilder<I, O> then(Step<?, ?> step) {
            this.thenStep = step;
            return this;
        }

        /**
         * Specifies the step to execute when the condition is false,
         * closes the branch, and returns to the outer builder.
         *
         * @param step the false-branch step
         * @return the parent workflow builder
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public WorkflowBuilder<I, O> otherwise(Step<?, ?> step) {
            if (thenStep == null) {
                throw new IllegalStateException("branch().then() must be called before otherwise()");
            }

            // Create a synthetic branch step
            String branchName = "branch-" + parent.nodeCounter.incrementAndGet();
            Step<Object, Object> thenTyped = (Step) thenStep;
            Step<Object, Object> otherwiseTyped = (Step) step;

            Step<Object, Object> branchStep = Step.named(branchName, (ctx, input) -> {
                if (predicate.test(input)) {
                    return thenTyped.execute(ctx, input);
                } else {
                    return otherwiseTyped.execute(ctx, input);
                }
            });

            WorkflowNode node = new WorkflowNode(branchName, NodeType.DETERMINISTIC, branchStep);
            parent.addNode(node);
            if (parent.lastNodeName() != null) {
                parent.addEdge(WorkflowEdge.of(parent.lastNodeName(), branchName));
            }
            parent.setLastNodeName(branchName);
            return parent;
        }
    }

    // -------------------------------------------------------------------------
    // DecisionBuilder
    // -------------------------------------------------------------------------

    /**
     * Builder for LLM-driven routing decisions within a workflow.
     * <p>
     * The routing LLM selects one named option at runtime based on the current input.
     *
     * @param <I> workflow input type
     * @param <O> workflow output type
     */
    public static final class DecisionBuilder<I, O> {

        private final WorkflowBuilder<I, O> parent;
        private final ChatClient routingClient;
        private final Map<String, Step<?, ?>> options = new LinkedHashMap<>();

        private DecisionBuilder(WorkflowBuilder<I, O> parent, ChatClient routingClient) {
            this.parent = parent;
            this.routingClient = Objects.requireNonNull(routingClient, "routingClient must not be null");
        }

        /**
         * Registers a named option that the LLM can choose.
         *
         * @param name the option name (used in the routing prompt)
         * @param step the step to execute when this option is chosen
         * @return this decision builder
         */
        public DecisionBuilder<I, O> option(String name, Step<?, ?> step) {
            Objects.requireNonNull(name, "option name must not be null");
            Objects.requireNonNull(step, "option step must not be null");
            if (options.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate decision option name: '" + name + "'");
            }
            options.put(name, step);
            return this;
        }

        /**
         * Closes the decision block and returns to the outer workflow builder.
         * <p>
         * Creates a single synthetic node in the graph that calls the routing LLM
         * and dispatches to the chosen option's step.
         *
         * @return the parent workflow builder
         * @throws IllegalStateException if no options have been registered
         */
        public WorkflowBuilder<I, O> end() {
            if (options.isEmpty()) {
                throw new IllegalStateException("decision() requires at least one .option()");
            }

            String decisionName = "decision-" + parent.nodeCounter.incrementAndGet();
            DecisionStep decisionStep = new DecisionStep(
                    decisionName, routingClient, options, DecisionStep.DEFAULT_PROMPT_TEMPLATE);

            WorkflowNode node = new WorkflowNode(decisionName, NodeType.AGENT, decisionStep);
            parent.addNode(node);
            if (parent.lastNodeName() != null) {
                parent.addEdge(WorkflowEdge.of(parent.lastNodeName(), decisionName));
            }
            parent.setLastNodeName(decisionName);
            return parent;
        }
    }
}
