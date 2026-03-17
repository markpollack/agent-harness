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

import java.time.Duration;

/**
 * Runtime constraints for workflow execution.
 * <p>
 * Not a DSL verb — it's a hint to the {@code WorkflowExecutor}.
 * Prevents {@code repeatUntil} loops from running indefinitely and
 * enforces cost budgets for agentic workflows where steps cost dollars.
 *
 * <pre>{@code
 * workflow.run(input, RunOptions.maxCost(5.0).maxIterations(10));
 * }</pre>
 *
 * @param maxCostUsd     maximum USD cost before the executor terminates (0 = unlimited)
 * @param maxIterations  maximum loop iterations (0 = unlimited)
 * @param maxDuration    maximum wall-clock duration (null = unlimited)
 */
public record RunOptions(double maxCostUsd, int maxIterations, Duration maxDuration) {

    /**
     * Creates RunOptions with a cost cap.
     *
     * @param usd the maximum cost in USD
     * @return a new RunOptions
     */
    public static RunOptions maxCost(double usd) {
        return new RunOptions(usd, 0, null);
    }

    /**
     * Creates RunOptions with an iteration cap.
     *
     * @param n the maximum number of iterations
     * @return a new RunOptions
     */
    public static RunOptions maxIterations(int n) {
        return new RunOptions(0, n, null);
    }

    /**
     * Creates RunOptions with a duration cap.
     *
     * @param duration the maximum wall-clock duration
     * @return a new RunOptions
     */
    public static RunOptions maxDuration(Duration duration) {
        return new RunOptions(0, 0, duration);
    }

    /**
     * Creates unlimited RunOptions (no constraints).
     *
     * @return RunOptions with no limits
     */
    public static RunOptions unlimited() {
        return new RunOptions(0, 0, null);
    }

    /**
     * Returns a copy with the given cost cap added.
     *
     * @param usd the maximum cost
     * @return a new RunOptions
     */
    public RunOptions withMaxCost(double usd) {
        return new RunOptions(usd, this.maxIterations, this.maxDuration);
    }

    /**
     * Returns a copy with the given iteration cap added.
     *
     * @param n the maximum iterations
     * @return a new RunOptions
     */
    public RunOptions withMaxIterations(int n) {
        return new RunOptions(this.maxCostUsd, n, this.maxDuration);
    }

    /**
     * Returns a copy with the given duration cap added.
     *
     * @param duration the maximum duration
     * @return a new RunOptions
     */
    public RunOptions withMaxDuration(Duration duration) {
        return new RunOptions(this.maxCostUsd, this.maxIterations, duration);
    }
}
