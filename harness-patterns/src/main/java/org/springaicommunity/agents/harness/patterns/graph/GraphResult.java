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

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Result of graph strategy execution.
 * <p>
 * This is NOT a LoopResult - it's specific to graph execution and contains
 * graph-specific information (path taken, stuck node, etc.).
 * <p>
 * Design rationale:
 * <ul>
 *   <li>GraphCompositionStrategy is not an AgentLoop, so it doesn't return LoopResult</li>
 *   <li>Graph failures (stuck in node) are distinct from loop failures</li>
 *   <li>Path tracking enables debugging and observability</li>
 * </ul>
 *
 * @param <O> the output type
 */
public final class GraphResult<O> {

    private final GraphStatus status;
    private final O output;
    private final List<String> pathTaken;
    private final String stuckNodeName;
    private final int iterations;
    private final Duration duration;
    private final Throwable error;

    private GraphResult(
            GraphStatus status,
            O output,
            List<String> pathTaken,
            String stuckNodeName,
            int iterations,
            Duration duration,
            Throwable error) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.output = output;
        this.pathTaken = List.copyOf(Objects.requireNonNull(pathTaken, "pathTaken must not be null"));
        this.stuckNodeName = stuckNodeName;
        this.iterations = iterations;
        this.duration = Objects.requireNonNull(duration, "duration must not be null");
        this.error = error;
    }

    /**
     * Creates a successful result.
     *
     * @param output the output value
     * @param pathTaken the nodes visited during execution
     * @param iterations the number of graph iterations
     * @param duration the total execution time
     * @param <O> the output type
     * @return a completed GraphResult
     */
    public static <O> GraphResult<O> completed(O output, List<String> pathTaken, int iterations, Duration duration) {
        return new GraphResult<>(GraphStatus.COMPLETED, output, pathTaken, null, iterations, duration, null);
    }

    /**
     * Creates a result for when the graph got stuck in a node.
     * <p>
     * This happens when a non-finish node has no valid outgoing edge
     * (graph topology failure).
     *
     * @param nodeName the name of the node where execution stuck
     * @param pathTaken the nodes visited before getting stuck
     * @param iterations the number of iterations before getting stuck
     * @param duration the total execution time
     * @param <O> the output type
     * @return a stuck GraphResult
     */
    public static <O> GraphResult<O> stuckInNode(String nodeName, List<String> pathTaken, int iterations, Duration duration) {
        return new GraphResult<>(GraphStatus.STUCK_IN_NODE, null, pathTaken, nodeName, iterations, duration, null);
    }

    /**
     * Creates a result for when max iterations were exceeded.
     *
     * @param pathTaken the nodes visited before hitting the limit
     * @param iterations the number of iterations (equals max)
     * @param duration the total execution time
     * @param <O> the output type
     * @return a max iterations GraphResult
     */
    public static <O> GraphResult<O> maxIterationsExceeded(List<String> pathTaken, int iterations, Duration duration) {
        return new GraphResult<>(GraphStatus.MAX_ITERATIONS, null, pathTaken, null, iterations, duration, null);
    }

    /**
     * Creates a result for when an error occurred during execution.
     *
     * @param error the error that occurred
     * @param pathTaken the nodes visited before the error
     * @param iterations the number of iterations before the error
     * @param duration the total execution time
     * @param <O> the output type
     * @return an error GraphResult
     */
    public static <O> GraphResult<O> error(Throwable error, List<String> pathTaken, int iterations, Duration duration) {
        return new GraphResult<>(GraphStatus.ERROR, null, pathTaken, null, iterations, duration, error);
    }

    /**
     * Returns the status of the graph execution.
     *
     * @return the status
     */
    public GraphStatus status() {
        return status;
    }

    /**
     * Returns the output if execution completed successfully.
     *
     * @return the output, or null if not completed
     */
    public O output() {
        return output;
    }

    /**
     * Returns the list of node names visited during execution.
     *
     * @return the path taken (immutable)
     */
    public List<String> pathTaken() {
        return pathTaken;
    }

    /**
     * Returns the name of the node where execution stuck.
     * Only set when status is STUCK_IN_NODE.
     *
     * @return the stuck node name, or null
     */
    public String stuckNodeName() {
        return stuckNodeName;
    }

    /**
     * Returns the number of graph iterations completed.
     *
     * @return the iteration count
     */
    public int iterations() {
        return iterations;
    }

    /**
     * Returns the total execution duration.
     *
     * @return the duration
     */
    public Duration duration() {
        return duration;
    }

    /**
     * Returns the error if status is ERROR.
     *
     * @return the error, or null
     */
    public Throwable error() {
        return error;
    }

    /**
     * Returns true if the graph completed successfully.
     *
     * @return true if status is COMPLETED
     */
    public boolean isSuccess() {
        return status == GraphStatus.COMPLETED;
    }

    /**
     * Returns true if the graph failed.
     *
     * @return true if status is not COMPLETED
     */
    public boolean isFailure() {
        return status != GraphStatus.COMPLETED;
    }

    /**
     * Status of graph execution.
     */
    public enum GraphStatus {
        /** Graph executed to completion */
        COMPLETED,
        /** Graph stuck in a node with no valid outgoing edge */
        STUCK_IN_NODE,
        /** Max iterations safety limit reached */
        MAX_ITERATIONS,
        /** An error occurred during execution */
        ERROR
    }

    @Override
    public String toString() {
        return "GraphResult[status=" + status +
                ", iterations=" + iterations +
                ", path=" + pathTaken +
                (stuckNodeName != null ? ", stuckIn=" + stuckNodeName : "") +
                (error != null ? ", error=" + error.getMessage() : "") +
                "]";
    }
}
