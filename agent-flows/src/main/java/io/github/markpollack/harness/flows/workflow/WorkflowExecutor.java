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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The runtime layer that executes a {@link WorkflowGraph}.
 * <p>
 * Dispatches each step, follows edges based on conditions, enforces
 * {@link RunOptions} constraints, and records every transition via
 * {@link TraceRecorder}.
 */
public class WorkflowExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutor.class);

    private static final int DEFAULT_MAX_ITERATIONS = 1000;

    private final PartitionHandler partitionHandler;
    private final TraceRecorder traceRecorder;

    /**
     * Creates an executor with a PartitionHandler and TraceRecorder.
     *
     * @param partitionHandler the substrate handler for step dispatch
     * @param traceRecorder    the recorder for step transitions
     */
    public WorkflowExecutor(PartitionHandler partitionHandler, TraceRecorder traceRecorder) {
        this.partitionHandler = partitionHandler != null ? partitionHandler : new LocalPartitionHandler();
        this.traceRecorder = traceRecorder != null ? traceRecorder : TraceRecorder.noop();
    }

    /**
     * Creates an executor with a TraceRecorder and default local handler.
     *
     * @param traceRecorder the recorder for step transitions
     */
    public WorkflowExecutor(TraceRecorder traceRecorder) {
        this(new LocalPartitionHandler(), traceRecorder);
    }

    /**
     * Creates an executor with defaults (local handler, no-op tracing).
     */
    public WorkflowExecutor() {
        this(new LocalPartitionHandler(), TraceRecorder.noop());
    }

    /**
     * Returns the partition handler used by this executor.
     *
     * @return the partition handler
     */
    public PartitionHandler partitionHandler() {
        return partitionHandler;
    }

    /**
     * Returns the trace recorder used by this executor.
     *
     * @return the trace recorder
     */
    public TraceRecorder traceRecorder() {
        return traceRecorder;
    }

    /**
     * Executes a workflow graph with the given context and input.
     *
     * @param graph   the compiled workflow graph
     * @param ctx     the execution context
     * @param input   the workflow input
     * @param options runtime constraints (may be null for no constraints)
     * @param <I>     input type
     * @param <O>     output type
     * @return the workflow output
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <I, O> O execute(WorkflowGraph<I, O> graph, AgentContext ctx, I input, RunOptions options) {
        int maxIterations = (options != null && options.maxIterations() > 0)
                ? options.maxIterations()
                : DEFAULT_MAX_ITERATIONS;

        String runId = ctx.runId();
        String currentNodeName = graph.startNode();
        Object currentInput = input;
        int iterations = 0;
        String previousNodeName = null;

        logger.debug("Starting workflow '{}' at node '{}'", graph.name(), currentNodeName);

        while (true) {
            if (++iterations > maxIterations) {
                throw new IllegalStateException(
                        "Workflow '" + graph.name() + "' exceeded max iterations: " + maxIterations);
            }

            String nodeName = currentNodeName;
            WorkflowNode node = graph.findNode(nodeName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Workflow '" + graph.name() + "': node '" + nodeName + "' not found"));

            logger.debug("Executing node '{}' (iteration {})", node.name(), iterations);

            // Execute the step through PartitionHandler and record the transition
            Instant stepStart = Instant.now();
            Object output;
            try {
                Step step = node.step();
                output = partitionHandler.execute(step, ctx, currentInput);
            } catch (WorkflowTerminatedException e) {
                Duration stepDuration = Duration.between(stepStart, Instant.now());
                recordTransition(runId, graph.name(), previousNodeName, node.name(),
                        stepDuration, 0L, 0.0, node.type());
                logger.info("Workflow '{}' terminated at node '{}': {} - {}",
                        graph.name(), node.name(), e.status(), e.getMessage());
                return null;
            } catch (Exception e) {
                Duration stepDuration = Duration.between(stepStart, Instant.now());
                recordTransition(runId, graph.name(), previousNodeName, node.name(),
                        stepDuration, 0L, 0.0, node.type());

                WorkflowEdge errorEdge = findErrorEdge(graph, currentNodeName, e);
                if (errorEdge != null) {
                    logger.debug("Error in node '{}', routing via error edge to '{}'",
                            currentNodeName, errorEdge.to());
                    previousNodeName = currentNodeName;
                    currentNodeName = errorEdge.to();
                    continue;
                }
                throw e;
            }

            Duration stepDuration = Duration.between(stepStart, Instant.now());
            recordTransition(runId, graph.name(), previousNodeName, node.name(),
                    stepDuration, 0L, 0.0, node.type());

            // Check if we're at the finish node
            if (currentNodeName.equals(graph.finishNode())) {
                logger.debug("Workflow '{}' completed at finish node '{}'", graph.name(), currentNodeName);
                return (O) output;
            }

            // Find next edge
            List<WorkflowEdge> outgoing = graph.edgesFrom(currentNodeName);
            WorkflowEdge nextEdge = null;
            for (WorkflowEdge edge : outgoing) {
                if (edge.matches(output)) {
                    nextEdge = edge;
                    break;
                }
            }

            if (nextEdge == null) {
                throw new IllegalStateException(
                        "Workflow '" + graph.name() + "' stuck at node '" + currentNodeName
                                + "': no matching outgoing edge");
            }

            currentInput = nextEdge.transform() != null
                    ? nextEdge.transform().apply(output)
                    : output;

            previousNodeName = currentNodeName;
            currentNodeName = nextEdge.to();
        }
    }

    /**
     * Executes a workflow graph with default options.
     */
    public <I, O> O execute(WorkflowGraph<I, O> graph, AgentContext ctx, I input) {
        return execute(graph, ctx, input, null);
    }

    private void recordTransition(String runId, String workflowName, String fromStep,
                                   String toStep, Duration duration, long tokens, double cost,
                                   io.github.markpollack.harness.patterns.graph.NodeType nodeType) {
        traceRecorder.record(new StepTransition(
                runId, workflowName, fromStep, toStep,
                Instant.now(), duration, tokens, cost, nodeType
        ));
    }

    private WorkflowEdge findErrorEdge(WorkflowGraph<?, ?> graph, String nodeName, Exception e) {
        List<WorkflowEdge> edges = graph.edgesFrom(nodeName);
        for (WorkflowEdge edge : edges) {
            if (edge.label() != null && edge.label().startsWith("error:")) {
                String errorClassName = edge.label().substring("error:".length());
                if (matchesException(e, errorClassName)) {
                    return edge;
                }
            }
        }
        return null;
    }

    private boolean matchesException(Exception e, String className) {
        Class<?> current = e.getClass();
        while (current != null) {
            if (current.getName().equals(className) || current.getSimpleName().equals(className)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
