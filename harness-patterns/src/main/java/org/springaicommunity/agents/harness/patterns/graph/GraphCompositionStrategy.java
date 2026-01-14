/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.agents.harness.patterns.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Graph-defined composition strategy for orchestrating work units.
 * <p>
 * This is a composition layer that hosts GraphNodes (which can wrap AgentLoops)
 * and executes them via graph traversal. GraphCompositionStrategy does NOT implement
 * AgentLoop - it's at a different abstraction level.
 * <p>
 * The name "CompositionStrategy" distinguishes this from:
 * <ul>
 *   <li>Graph algorithms (shortest path, traversal)</li>
 *   <li>Graph databases</li>
 *   <li>Graph data structures</li>
 * </ul>
 * <p>
 * Design:
 * <ul>
 *   <li>Graph is built before execution (immutable after construction)</li>
 *   <li>Execution follows edges based on conditions</li>
 *   <li>First matching edge is taken (order matters)</li>
 *   <li>Max iterations safety guard prevents infinite loops</li>
 *   <li>Stuck node detection for graph topology failures</li>
 * </ul>
 *
 * @param <I> the input type for the graph
 * @param <O> the output type from the graph
 */
public final class GraphCompositionStrategy<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(GraphCompositionStrategy.class);

    private final String name;
    private final Map<String, GraphNode<?, ?>> nodes;
    private final Map<String, List<GraphEdge<?>>> edges;
    private final String startNodeName;
    private final String finishNodeName;
    private final int maxIterations;

    /**
     * Package-private constructor. Use {@link GraphCompositionStrategyBuilder} to create instances.
     */
    GraphCompositionStrategy(
            String name,
            Map<String, GraphNode<?, ?>> nodes,
            Map<String, List<GraphEdge<?>>> edges,
            String startNodeName,
            String finishNodeName,
            int maxIterations) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.nodes = Map.copyOf(nodes);
        this.edges = Map.copyOf(edges);
        this.startNodeName = Objects.requireNonNull(startNodeName, "startNodeName must not be null");
        this.finishNodeName = Objects.requireNonNull(finishNodeName, "finishNodeName must not be null");
        this.maxIterations = maxIterations;
    }

    /**
     * Creates a new builder for a graph composition strategy.
     *
     * @param name the name of the graph composition strategy
     * @param <I> the input type
     * @param <O> the output type
     * @return a new builder
     */
    public static <I, O> GraphCompositionStrategyBuilder<I, O> builder(String name) {
        return new GraphCompositionStrategyBuilder<>(name);
    }

    /**
     * Returns the name of this graph composition strategy.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the maximum iterations allowed.
     *
     * @return the max iterations
     */
    public int maxIterations() {
        return maxIterations;
    }

    /**
     * Executes the graph strategy with the given input.
     * <p>
     * Execution starts at the start node and follows edges until reaching
     * the finish node or hitting a termination condition (max iterations,
     * stuck node, or error).
     *
     * @param input the input to the graph
     * @return the result of graph execution
     */
    public GraphResult<O> execute(I input) {
        GraphContext context = new GraphContext(name);
        return executeWithContext(context, input);
    }

    /**
     * Executes the graph strategy with a specific context (for testing or chaining).
     *
     * @param context the execution context
     * @param input the input to the graph
     * @return the result of graph execution
     */
    public GraphResult<O> executeWithContext(GraphContext context, I input) {
        Instant startTime = Instant.now();
        List<String> path = new ArrayList<>();
        int iterations = 0;

        GraphNode<?, ?> current = nodes.get(startNodeName);
        Object currentInput = input;

        logger.debug("Starting graph execution: {} with input type {}", name, input != null ? input.getClass().getSimpleName() : "null");

        try {
            while (true) {
                // Safety check
                if (++iterations > maxIterations) {
                    logger.warn("Graph {} exceeded max iterations: {}", name, maxIterations);
                    Duration duration = Duration.between(startTime, Instant.now());
                    return GraphResult.maxIterationsExceeded(path, iterations, duration);
                }

                // Track path
                path.add(current.name());
                logger.debug("Executing node: {} (iteration {})", current.name(), iterations);

                // Execute current node
                Object nodeOutput = executeNodeUnsafe(current, context, currentInput);
                logger.trace("Node {} produced output: {}", current.name(), nodeOutput);

                // Check if we're at the finish node
                if (current.name().equals(finishNodeName)) {
                    Duration duration = Duration.between(startTime, Instant.now());
                    logger.debug("Graph {} completed successfully in {} iterations", name, iterations);
                    @SuppressWarnings("unchecked")
                    O typedOutput = (O) nodeOutput;
                    return GraphResult.completed(typedOutput, path, iterations, duration);
                }

                // Find matching edge
                GraphEdge<?> edge = resolveEdge(current.name(), nodeOutput);
                if (edge == null) {
                    // Graph topology failure - stuck in node
                    logger.error("Graph {} stuck in node {}: no valid outgoing edge", name, current.name());
                    Duration duration = Duration.between(startTime, Instant.now());
                    return GraphResult.stuckInNode(current.name(), path, iterations, duration);
                }

                // Move to next node
                GraphNode<?, ?> nextNode = nodes.get(edge.targetNodeName());
                currentInput = transformOutputUnsafe(edge, nodeOutput);
                current = nextNode;
                logger.trace("Transitioning to node: {}", current.name());
            }
        } catch (Exception e) {
            logger.error("Graph {} error in node {}: {}", name, current.name(), e.getMessage(), e);
            Duration duration = Duration.between(startTime, Instant.now());
            return GraphResult.error(e, path, iterations, duration);
        }
    }

    /**
     * Resolves the edge to take from a node given its output.
     * <p>
     * Edges are evaluated in order; the first matching edge is returned.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private GraphEdge<?> resolveEdge(String nodeName, Object output) {
        List<GraphEdge<?>> nodeEdges = edges.get(nodeName);
        if (nodeEdges == null || nodeEdges.isEmpty()) {
            return null;
        }

        for (GraphEdge edge : nodeEdges) {
            if (edge.matches(output)) {
                return edge;
            }
        }
        return null;
    }

    /**
     * Executes a node without type safety (internal use only).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object executeNodeUnsafe(GraphNode node, GraphContext context, Object input) {
        return node.execute(context, input);
    }

    /**
     * Transforms output without type safety (internal use only).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object transformOutputUnsafe(GraphEdge edge, Object output) {
        return edge.transformOutput(output);
    }

    @Override
    public String toString() {
        return "GraphCompositionStrategy[" + name +
                ", nodes=" + nodes.keySet() +
                ", start=" + startNodeName +
                ", finish=" + finishNodeName +
                "]";
    }
}
