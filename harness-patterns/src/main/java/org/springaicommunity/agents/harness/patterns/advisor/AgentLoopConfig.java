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
package org.springaicommunity.agents.harness.patterns.advisor;

import java.time.Duration;

/**
 * Configuration for {@link AgentLoopAdvisor}.
 * <p>
 * Provides sensible defaults for all settings. Use the static factory methods
 * or builder to create configurations.
 *
 * @param maxTurns maximum number of turns (LLM call + tool execution cycles) allowed
 * @param timeout maximum duration for the entire loop
 * @param costLimit maximum cost in dollars before termination (0 = disabled)
 * @param stuckThreshold consecutive identical outputs before detecting stuck (0 = disabled)
 * @param juryEvaluationInterval evaluate with jury every N turns (0 = disabled)
 */
public record AgentLoopConfig(
        int maxTurns,
        Duration timeout,
        double costLimit,
        int stuckThreshold,
        int juryEvaluationInterval
) {
    // Default values
    public static final int DEFAULT_MAX_TURNS = 20;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    public static final double DEFAULT_COST_LIMIT = 5.0;
    public static final int DEFAULT_STUCK_THRESHOLD = 3;

    /**
     * Compact constructor with validation.
     */
    public AgentLoopConfig {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be at least 1, got: " + maxTurns);
        }
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        if (costLimit < 0) {
            throw new IllegalArgumentException("costLimit must not be negative");
        }
        if (stuckThreshold < 0) {
            throw new IllegalArgumentException("stuckThreshold must not be negative");
        }
        if (juryEvaluationInterval < 0) {
            throw new IllegalArgumentException("juryEvaluationInterval must not be negative");
        }
    }

    /**
     * Returns a configuration with default values.
     */
    public static AgentLoopConfig defaults() {
        return new AgentLoopConfig(
                DEFAULT_MAX_TURNS,
                DEFAULT_TIMEOUT,
                DEFAULT_COST_LIMIT,
                DEFAULT_STUCK_THRESHOLD,
                0  // jury disabled by default
        );
    }

    /**
     * Returns a configuration optimized for CLI/interactive use.
     * <p>
     * Lower limits to provide responsive feedback.
     */
    public static AgentLoopConfig forCli() {
        return new AgentLoopConfig(
                20,
                Duration.ofMinutes(5),
                5.0,
                3,
                0
        );
    }

    /**
     * Returns a configuration optimized for benchmarks.
     * <p>
     * Higher limits to allow longer-running tasks.
     */
    public static AgentLoopConfig forBenchmark() {
        return new AgentLoopConfig(
                50,
                Duration.ofMinutes(30),
                10.0,
                3,
                5  // evaluate every 5 turns
        );
    }

    /**
     * Returns a configuration optimized for autonomous mode.
     * <p>
     * Strict limits to fail fast.
     */
    public static AgentLoopConfig forAutonomous() {
        return new AgentLoopConfig(
                30,
                Duration.ofMinutes(10),
                2.0,
                2,  // fail fast if stuck
                0
        );
    }
}
