package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;
import io.github.markpollack.workflow.flows.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Graph walker that executes a {@link WorkflowGraph} using Java 21 pattern matching switch.
 * <p>
 * Each {@link WorkflowNode} variant has its own handler. The executor dispatches steps
 * through a {@link StepRunner} and records transitions via {@link TraceRecorder}.
 */
public class WorkflowExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutor.class);
    private static final int DEFAULT_MAX_ITERATIONS = 1000;

    private final StepRunner stepRunner;
    private final TraceRecorder traceRecorder;
    private final ExecutorService parallelExecutor;

    public WorkflowExecutor(StepRunner stepRunner, TraceRecorder traceRecorder,
                            ExecutorService parallelExecutor) {
        this.stepRunner = stepRunner != null ? stepRunner : new LocalStepRunner();
        this.traceRecorder = traceRecorder != null ? traceRecorder : TraceRecorder.noop();
        this.parallelExecutor = parallelExecutor != null ? parallelExecutor :
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    }

    public WorkflowExecutor(StepRunner stepRunner, TraceRecorder traceRecorder) {
        this(stepRunner, traceRecorder, null);
    }

    public WorkflowExecutor(TraceRecorder traceRecorder) {
        this(new LocalStepRunner(), traceRecorder, null);
    }

    public WorkflowExecutor() {
        this(new LocalStepRunner(), TraceRecorder.noop(), null);
        logger.warn("Using default WorkflowExecutor — StepRunner and TraceRecorder are unconfigured");
    }

    public StepRunner stepRunner() { return stepRunner; }
    public TraceRecorder traceRecorder() { return traceRecorder; }

    private record BranchResult(Object output, AgentContext contextAfter) {}

    public <I, O> O execute(WorkflowGraph<I, O> graph, AgentContext ctx, I input, RunOptions options) {
        return execute(graph, ctx, input, options, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <I, O> O execute(WorkflowGraph<I, O> graph, AgentContext ctx, I input, RunOptions options,
                             AtomicReference<AgentContext> contextOut) {
        int maxIterations = (options != null && options.maxIterations() > 0)
                ? options.maxIterations() : DEFAULT_MAX_ITERATIONS;

        String runId = ctx.runId();
        String currentNodeName = graph.startNode();
        Object currentValue = input;
        int iterations = 0;
        String previousNodeName = null;

        // Per-loop iteration counters: "loop.<nodeName>.iteration" → count
        Map<String, Integer> loopCounters = new HashMap<>();
        // Pending fork futures: joinNodeName → ordered branch futures
        Map<String, List<Future<BranchResult>>> pendingForks = new HashMap<>();
        // Fork input values: joinNodeName → input value at fork time (for ENRICHMENT passthrough)
        Map<String, Object> pendingForkInputs = new HashMap<>();

        logger.debug("Starting workflow '{}' at node '{}'", graph.name(), currentNodeName);

        while (true) {
            if (++iterations > maxIterations) {
                throw new IllegalStateException(
                        "Workflow '" + graph.name() + "' exceeded max iterations: " + maxIterations);
            }

            WorkflowNode node = graph.nodeByName(currentNodeName);
            logger.debug("Processing node '{}' [{}] (iteration {})", node.name(), node.getClass().getSimpleName(), iterations);

            switch (node) {

                case WorkflowNode.StepNode sn -> {
                    Instant stepStart = Instant.now();
                    Object output;
                    Step step = sn.step();
                    // Capture for sub-workflow inline execution (null for regular steps)
                    AtomicReference<AgentContext> subCtxCapture = null;
                    try {
                        if (step instanceof Workflow<?, ?> subWorkflow) {
                            // Sub-workflows run inline — bypasses stepRunner so Temporal and
                            // other remote runners only see leaf steps as activities.
                            // Inherits this executor so leaf steps inside the sub-workflow use
                            // the same StepRunner (checkpointing, Temporal, etc.) as the parent.
                            subCtxCapture = new AtomicReference<>(ctx);
                            output = subWorkflow.resolveExecutorOr(this).execute(
                                    (WorkflowGraph) subWorkflow.graph(), ctx, currentValue, null, subCtxCapture);
                        } else {
                            output = stepRunner.execute(step, ctx, currentValue);
                        }
                    } catch (WorkflowAbortException e) {
                        recordTransition(runId, graph.name(), previousNodeName, sn.name(),
                                Duration.between(stepStart, Instant.now()), 0L, 0.0, sn.type(), "abort");
                        logger.info("Workflow '{}' aborted at '{}' with result",
                                graph.name(), sn.name());
                        if (contextOut != null) contextOut.set(ctx);
                        return (O) e.getResult();
                    } catch (WorkflowTerminatedException e) {
                        recordTransition(runId, graph.name(), previousNodeName, sn.name(),
                                Duration.between(stepStart, Instant.now()), 0L, 0.0, sn.type(), null);
                        logger.info("Workflow '{}' terminated at '{}': {} - {}",
                                graph.name(), sn.name(), e.status(), e.getMessage());
                        if (contextOut != null) contextOut.set(ctx);
                        return null;
                    } catch (Exception e) {
                        Duration dur = Duration.between(stepStart, Instant.now());
                        recordTransition(runId, graph.name(), previousNodeName, sn.name(),
                                dur, 0L, 0.0, sn.type(), null);
                        // Check for error edges
                        WorkflowEdge errorEdge = graph.errorEdge(currentNodeName, e);
                        if (errorEdge != null) {
                            logger.debug("Error in '{}', routing to '{}'", currentNodeName, errorEdge.to());
                            previousNodeName = currentNodeName;
                            currentNodeName = errorEdge.to();
                            continue;
                        }
                        throw e;
                    }
                    recordTransition(runId, graph.name(), previousNodeName, sn.name(),
                            Duration.between(stepStart, Instant.now()), 0L, 0.0, sn.type(), null);
                    currentValue = output;

                    if (subCtxCapture != null) {
                        // Sub-workflow: merge its final context back into the parent
                        ctx = ctx.mergeFrom(subCtxCapture.get());
                        ctx = ctx.mutate()
                                .with(io.github.markpollack.workflow.flows.steps.Steps.outputOf(step.name()), output)
                                .build();
                    } else {
                        // Regular step: auto-propagate output and call updateContext()
                        ctx = ctx.mutate()
                                .with(io.github.markpollack.workflow.flows.steps.Steps.outputOf(step.name()), output)
                                .build();
                        @SuppressWarnings("unchecked")
                        Step<Object, Object> typedStep = (Step<Object, Object>) step;
                        ctx = typedStep.updateContext(ctx, output);
                    }

                    // Advance to next node
                    previousNodeName = currentNodeName;

                    // Check for back-edges (cyclic paths) before normal forward progression
                    WorkflowEdge backEdge = findBackEdge(graph, currentNodeName, currentValue);
                    if (backEdge != null) {
                        String counterKey = "backTo." + currentNodeName;
                        int count = loopCounters.getOrDefault(counterKey, 0) + 1;
                        loopCounters.put(counterKey, count);
                        if (count >= maxIterations) {
                            throw new IllegalStateException("Back-edge from '" + currentNodeName
                                    + "' exceeded max iterations: " + maxIterations);
                        }
                        logger.debug("Taking back-edge from '{}' to '{}' (cycle {})",
                                currentNodeName, backEdge.to(), count);
                        currentNodeName = backEdge.to();
                    } else {
                        String next = findNonErrorSuccessor(graph, currentNodeName);
                        if (next == null) {
                            // Terminal node (finish or only-error-edges)
                            if (contextOut != null) contextOut.set(ctx);
                            return (O) currentValue;
                        }
                        currentNodeName = next;
                    }
                }

                case WorkflowNode.GatewayNode gn -> {
                    Instant start = Instant.now();
                    boolean result = gn.predicate().test(currentValue);
                    recordTransition(runId, graph.name(), previousNodeName, gn.name(),
                            Duration.between(start, Instant.now()), 0L, 0.0, gn.type(),
                            result ? "true" : "false");
                    // currentValue passes through unchanged
                    previousNodeName = currentNodeName;
                    // Follow BooleanGuard edge matching the result
                    EdgeCondition target = new EdgeCondition.BooleanGuard(result);
                    currentNodeName = findEdgeByCondition(graph, gn.name(), target);
                }

                case WorkflowNode.DecisionNode dn -> {
                    Instant start = Instant.now();
                    Step routingStep = dn.routingStep();
                    Object chosenLabel = stepRunner.execute(routingStep, ctx, currentValue);
                    String chosen = chosenLabel.toString().strip();
                    recordTransition(runId, graph.name(), previousNodeName, dn.name(),
                            Duration.between(start, Instant.now()), 0L, 0.0, dn.type(), chosen);
                    // Follow OptionMatch edge
                    previousNodeName = currentNodeName;
                    EdgeCondition target = new EdgeCondition.OptionMatch(chosen);
                    currentNodeName = findEdgeByCondition(graph, dn.name(), target);
                }

                case WorkflowNode.GateNode gateNode -> {
                    Instant start = Instant.now();
                    @SuppressWarnings("unchecked")
                    Gate<Object> gate = (Gate<Object>) gateNode.gate();
                    GateDecision decision = gate.evaluate(ctx, currentValue);
                    ctx = gate.updateContext(ctx, currentValue, decision);

                    // Verdict feedback: write Verdict to context on FAIL
                    if (decision == GateDecision.FAIL || decision == GateDecision.ESCALATE) {
                        Object verdict = null;
                        if (gate instanceof JudgeGate<?> jg) verdict = jg.lastVerdict();
                        else if (gate instanceof TieredGate<?> tg) verdict = tg.lastVerdict();
                        if (verdict != null) {
                            ctx = ctx.mutate()
                                    .with(AgentContext.JUDGE_VERDICT, verdict)
                                    .build();
                            // Run reflector if configured
                            if (gateNode.reflector() != null) {
                                @SuppressWarnings("unchecked")
                                Step<Object, Object> reflector = (Step<Object, Object>) gateNode.reflector();
                                Object reflection = stepRunner.execute(reflector, ctx, verdict);
                                ctx = ctx.mutate()
                                        .with(AgentContext.JUDGE_REFLECTION, reflection.toString())
                                        .build();
                            }
                        }
                    }

                    String label = decision.name().toLowerCase();
                    recordTransition(runId, graph.name(), previousNodeName, gateNode.name(),
                            Duration.between(start, Instant.now()), 0L, 0.0, gateNode.type(), label);
                    // currentValue passes through unchanged
                    previousNodeName = currentNodeName;
                    EdgeCondition gateTarget = new EdgeCondition.GateMatch(decision);
                    currentNodeName = findEdgeByCondition(graph, gateNode.name(), gateTarget);
                }

                case WorkflowNode.LoopEntryNode le -> {
                    // Increment per-loop counter
                    String loopKey = "loop." + le.name() + ".iteration";
                    int iter = loopCounters.getOrDefault(loopKey, 0);
                    loopCounters.put(loopKey, iter);

                    // Set ITERATION_COUNT for the developer
                    ctx = ctx.mutate()
                            .with(AgentContext.ITERATION_COUNT, iter)
                            .build();

                    // Enforce maxIterations
                    if (iter > maxIterations) {
                        throw new IllegalStateException("Loop '" + le.name()
                                + "' exceeded max iterations: " + maxIterations);
                    }

                    Instant start = Instant.now();
                    boolean shouldExit = le.exitCondition().test(ctx);
                    recordTransition(runId, graph.name(), previousNodeName, le.name(),
                            Duration.between(start, Instant.now()), 0L, 0.0, le.type(),
                            shouldExit ? "exit" : "continue");

                    previousNodeName = currentNodeName;
                    if (shouldExit) {
                        currentNodeName = findEdgeByCondition(graph, le.name(), new EdgeCondition.LoopExit());
                    } else {
                        currentNodeName = findEdgeByCondition(graph, le.name(), new EdgeCondition.LoopContinue());
                        loopCounters.put(loopKey, iter + 1);
                    }
                }

                case WorkflowNode.LoopCheckNode lc -> {
                    // Increment per-loop counter
                    String loopKey = "loop." + lc.name() + ".iteration";
                    int iter = loopCounters.getOrDefault(loopKey, 0);
                    loopCounters.put(loopKey, iter + 1);

                    ctx = ctx.mutate()
                            .with(AgentContext.ITERATION_COUNT, iter)
                            .build();

                    if (iter >= maxIterations) {
                        throw new IllegalStateException("Loop '" + lc.name()
                                + "' exceeded max iterations: " + maxIterations);
                    }

                    Instant start = Instant.now();
                    boolean shouldExit = lc.outputExitCondition().test(currentValue);
                    recordTransition(runId, graph.name(), previousNodeName, lc.name(),
                            Duration.between(start, Instant.now()), 0L, 0.0, lc.type(),
                            shouldExit ? "exit" : "continue");

                    previousNodeName = currentNodeName;
                    if (shouldExit) {
                        currentNodeName = findEdgeByCondition(graph, lc.name(), new EdgeCondition.LoopExit());
                    } else {
                        currentNodeName = findEdgeByCondition(graph, lc.name(), new EdgeCondition.LoopContinue());
                    }
                }

                case WorkflowNode.LoopExitNode lx -> {
                    // Passthrough — follow unconditional successor
                    recordTransition(runId, graph.name(), previousNodeName, lx.name(),
                            Duration.ZERO, 0L, 0.0, lx.type(), null);
                    previousNodeName = currentNodeName;
                    String next = graph.unconditionalSuccessor(lx.name());
                    if (next == null) {
                        if (contextOut != null) contextOut.set(ctx);
                        return (O) currentValue;
                    }
                    currentNodeName = next;
                }

                case WorkflowNode.ForkNode fn -> {
                    Instant start = Instant.now();
                    // Dispatch branches concurrently
                    List<WorkflowEdge> branchEdges = graph.edgesFrom(fn.name()).stream()
                            .filter(e -> e.condition() instanceof EdgeCondition.BranchIndex)
                            .toList();

                    final Object forkInput = currentValue;
                    final AgentContext forkCtx = ctx;
                    List<Future<BranchResult>> futures = new ArrayList<>();
                    for (WorkflowEdge be : branchEdges) {
                        String branchNodeName = be.to();
                        WorkflowNode branchNode = graph.nodeByName(branchNodeName);
                        if (branchNode instanceof WorkflowNode.StepNode bsn) {
                            futures.add(parallelExecutor.submit(() -> {
                                Step<Object, Object> typedBranchStep = (Step<Object, Object>) bsn.step();
                                Object out;
                                AgentContext branchCtx;
                                if (typedBranchStep instanceof Workflow<?, ?> subWorkflow) {
                                    AtomicReference<AgentContext> subCapture = new AtomicReference<>(forkCtx);
                                    out = subWorkflow.resolveExecutorOr(WorkflowExecutor.this).execute(
                                            (WorkflowGraph) subWorkflow.graph(), forkCtx, forkInput, null, subCapture);
                                    branchCtx = forkCtx.mergeFrom(subCapture.get());
                                    branchCtx = branchCtx.mutate()
                                            .with(io.github.markpollack.workflow.flows.steps.Steps.outputOf(typedBranchStep.name()), out)
                                            .build();
                                } else {
                                    out = stepRunner.execute(typedBranchStep, forkCtx, forkInput);
                                    branchCtx = forkCtx.mutate()
                                            .with(io.github.markpollack.workflow.flows.steps.Steps.outputOf(typedBranchStep.name()), out)
                                            .build();
                                    branchCtx = typedBranchStep.updateContext(branchCtx, out);
                                }
                                return new BranchResult(out, branchCtx);
                            }));
                        }
                    }

                    // Jump directly to the join — no edge traversal needed
                    pendingForks.put(fn.joinNodeName(), futures);
                    pendingForkInputs.put(fn.joinNodeName(), forkInput);
                    recordTransition(runId, graph.name(), previousNodeName, fn.name(),
                            Duration.between(start, Instant.now()), 0L, 0.0, fn.type(), null);
                    previousNodeName = currentNodeName;
                    currentNodeName = fn.joinNodeName();
                }

                case WorkflowNode.JoinNode jn -> {
                    Instant start = Instant.now();
                    // Retrieve futures keyed by this join's name (written by ForkNode handler)
                    List<Future<BranchResult>> futures = pendingForks.remove(jn.name());

                    if (futures != null) {
                        // Parallel fork join: merge context from all branches
                        List<Object> results = new ArrayList<>();
                        for (Future<BranchResult> f : futures) {
                            try {
                                BranchResult br = f.get();
                                results.add(br.output());
                                ctx = ctx.mergeFrom(br.contextAfter());
                            } catch (Exception e) {
                                throw new RuntimeException("Parallel branch failed", e);
                            }
                        }
                        if (jn.joinMode() == WorkflowNode.JoinMode.COLLECTION) {
                            // Collection join: downstream receives List<Object> of branch outputs
                            currentValue = results;
                        } else {
                            // Enrichment join: input passes through; branches wrote to context keys
                            currentValue = pendingForkInputs.remove(jn.name());
                        }
                    } else {
                        pendingForkInputs.remove(jn.name()); // clean up if present
                    }
                    // XOR-join (branch/decision/gate): currentValue already set by the taken branch

                    recordTransition(runId, graph.name(), previousNodeName, jn.name(),
                            Duration.between(start, Instant.now()), 0L, 0.0, jn.type(), null);
                    previousNodeName = currentNodeName;
                    String next = graph.unconditionalSuccessor(jn.name());
                    if (next == null) {
                        if (contextOut != null) contextOut.set(ctx);
                        return (O) currentValue;
                    }
                    currentNodeName = next;
                }
            }
        }
    }

    public <I, O> O execute(WorkflowGraph<I, O> graph, AgentContext ctx, I input) {
        return execute(graph, ctx, input, null, null);
    }

    // -- Helpers --

    private String findNonErrorSuccessor(WorkflowGraph<?, ?> graph, String nodeName) {
        List<WorkflowEdge> edges = graph.edgesFrom(nodeName);
        for (WorkflowEdge edge : edges) {
            if (!(edge.condition() instanceof EdgeCondition.ErrorMatch)
                    && !(edge.condition() instanceof EdgeCondition.BackEdge)) {
                return edge.to();
            }
        }
        // All edges are error/back edges or no edges — terminal
        return null;
    }

    private WorkflowEdge findBackEdge(WorkflowGraph<?, ?> graph, String nodeName, Object currentValue) {
        for (WorkflowEdge edge : graph.edgesFrom(nodeName)) {
            if (edge.condition() instanceof EdgeCondition.BackEdge be && be.condition().test(currentValue)) {
                return edge;
            }
        }
        return null;
    }

    private String findEdgeByCondition(WorkflowGraph<?, ?> graph, String nodeName, EdgeCondition target) {
        for (WorkflowEdge edge : graph.edgesFrom(nodeName)) {
            if (edge.condition().equals(target)) {
                return edge.to();
            }
        }
        throw new IllegalStateException("No edge matching " + target + " from node '" + nodeName + "'");
    }

    private void recordTransition(String runId, String workflowName, String fromStep,
                                   String toStep, Duration duration, long tokens, double cost,
                                   io.github.markpollack.workflow.patterns.graph.NodeType nodeType,
                                   String label) {
        traceRecorder.record(new StepTransition(
                runId, workflowName, fromStep, toStep,
                Instant.now(), duration, tokens, cost, nodeType, label
        ));
    }
}
