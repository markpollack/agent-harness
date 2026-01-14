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

import java.util.List;

/**
 * Exception thrown during graph strategy execution or construction.
 * <p>
 * This exception hierarchy captures graph-specific failures that are distinct
 * from loop failures.
 */
public class GraphExecutionException extends RuntimeException {

    private final String graphName;
    private final List<String> pathTaken;

    /**
     * Creates a new graph execution exception.
     *
     * @param message the error message
     * @param graphName the name of the graph
     * @param pathTaken the path taken before the exception
     */
    public GraphExecutionException(String message, String graphName, List<String> pathTaken) {
        super(message);
        this.graphName = graphName;
        this.pathTaken = pathTaken != null ? List.copyOf(pathTaken) : List.of();
    }

    /**
     * Creates a new graph execution exception with a cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     * @param graphName the name of the graph
     * @param pathTaken the path taken before the exception
     */
    public GraphExecutionException(String message, Throwable cause, String graphName, List<String> pathTaken) {
        super(message, cause);
        this.graphName = graphName;
        this.pathTaken = pathTaken != null ? List.copyOf(pathTaken) : List.of();
    }

    /**
     * Returns the name of the graph where the exception occurred.
     *
     * @return the graph name
     */
    public String graphName() {
        return graphName;
    }

    /**
     * Returns the path taken before the exception occurred.
     *
     * @return the path taken (immutable)
     */
    public List<String> pathTaken() {
        return pathTaken;
    }

    /**
     * Exception thrown when the graph gets stuck in a node with no valid outgoing edge.
     * <p>
     * This is a graph topology failure - the node is not the finish node but has no
     * edges whose conditions match the current output.
     */
    public static class StuckInNodeException extends GraphExecutionException {

        private final String nodeName;
        private final Object nodeOutput;

        /**
         * Creates a stuck in node exception.
         *
         * @param graphName the graph name
         * @param nodeName the node where execution stuck
         * @param nodeOutput the output that had no matching edge
         * @param pathTaken the path taken before getting stuck
         */
        public StuckInNodeException(String graphName, String nodeName, Object nodeOutput, List<String> pathTaken) {
            super(String.format("Graph '%s' stuck in node '%s' - no valid outgoing edge for output",
                    graphName, nodeName), graphName, pathTaken);
            this.nodeName = nodeName;
            this.nodeOutput = nodeOutput;
        }

        /**
         * Returns the name of the node where execution stuck.
         *
         * @return the node name
         */
        public String nodeName() {
            return nodeName;
        }

        /**
         * Returns the output that had no matching edge.
         *
         * @return the node output
         */
        public Object nodeOutput() {
            return nodeOutput;
        }
    }

    /**
     * Exception thrown when the graph exceeds its maximum iteration limit.
     * <p>
     * This is a safety guard to prevent infinite loops in cyclic graphs.
     */
    public static class MaxIterationsExceededException extends GraphExecutionException {

        private final int maxIterations;
        private final int actualIterations;

        /**
         * Creates a max iterations exceeded exception.
         *
         * @param graphName the graph name
         * @param maxIterations the configured maximum
         * @param actualIterations the iterations executed
         * @param pathTaken the path taken before hitting the limit
         */
        public MaxIterationsExceededException(String graphName, int maxIterations, int actualIterations, List<String> pathTaken) {
            super(String.format("Graph '%s' exceeded max iterations: %d (max %d)",
                    graphName, actualIterations, maxIterations), graphName, pathTaken);
            this.maxIterations = maxIterations;
            this.actualIterations = actualIterations;
        }

        /**
         * Returns the configured maximum iterations.
         *
         * @return the max iterations
         */
        public int maxIterations() {
            return maxIterations;
        }

        /**
         * Returns the actual number of iterations executed.
         *
         * @return the actual iterations
         */
        public int actualIterations() {
            return actualIterations;
        }
    }

    /**
     * Exception thrown when a graph has invalid structure.
     * <p>
     * This is thrown at build/compile time, not execution time.
     */
    public static class InvalidGraphException extends RuntimeException {

        private final String graphName;

        /**
         * Creates an invalid graph exception.
         *
         * @param message the validation error message
         * @param graphName the graph name
         */
        public InvalidGraphException(String message, String graphName) {
            super(String.format("Invalid graph '%s': %s", graphName, message));
            this.graphName = graphName;
        }

        /**
         * Returns the name of the invalid graph.
         *
         * @return the graph name
         */
        public String graphName() {
            return graphName;
        }
    }
}
