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

import java.util.List;

/**
 * The runtime layer that executes a {@link WorkflowGraph}.
 * <p>
 * Dispatches each step, follows edges based on conditions, and enforces
 * {@link RunOptions} constraints. In Stage 1 this is a minimal local executor;
 * {@code TraceRecorder} and {@code PartitionHandler} are added in Steps 1.4/1.5.
 */
public class WorkflowExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutor.class);

    private static final int DEFAULT_MAX_ITERATIONS = 1000;

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

        String currentNodeName = graph.startNode();
        Object currentInput = input;
        int iterations = 0;

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

            // Execute the step
            Object output;
            try {
                Step step = node.step();
                output = step.execute(ctx, currentInput);
            } catch (WorkflowTerminatedException e) {
                logger.info("Workflow '{}' terminated at node '{}': {} - {}",
                        graph.name(), node.name(), e.status(), e.getMessage());
                return null; // terminated workflows return null
            } catch (Exception e) {
                // Check for error edges from this node
                WorkflowEdge errorEdge = findErrorEdge(graph, currentNodeName, e);
                if (errorEdge != null) {
                    logger.debug("Error in node '{}', routing via error edge to '{}'",
                            currentNodeName, errorEdge.to());
                    currentNodeName = errorEdge.to();
                    currentInput = currentInput; // keep existing input for recovery step
                    continue;
                }
                throw e; // no matching error edge — propagate
            }

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

            // Apply transform if present
            currentInput = nextEdge.transform() != null
                    ? nextEdge.transform().apply(output)
                    : output;

            currentNodeName = nextEdge.to();
        }
    }

    /**
     * Executes a workflow graph with default options.
     */
    public <I, O> O execute(WorkflowGraph<I, O> graph, AgentContext ctx, I input) {
        return execute(graph, ctx, input, null);
    }

    /**
     * Finds an error edge that matches the thrown exception.
     * Error edges have labels starting with "error:" followed by the exception class name.
     */
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
