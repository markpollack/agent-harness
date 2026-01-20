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
package org.springaicommunity.agents.harness.patterns.turnlimited;

import org.springaicommunity.judge.jury.Jury;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Configuration for Turn-Limited Multi-Condition loop pattern.
 * <p>
 * This pattern is used by Claude CLI, Gemini CLI, Swarm, and SWE-Agent.
 * The loop executes tool calls until one of several termination conditions is met.
 *
 * <p>Termination conditions (checked in order):
 * <ol>
 *   <li>Abort signal received (user interrupt)</li>
 *   <li>Max turns reached</li>
 *   <li>Timeout exceeded</li>
 *   <li>Finish tool called (complete_task)</li>
 *   <li>No tool calls in response</li>
 *   <li>Jury verdict passes (if jury configured)</li>
 *   <li>Score threshold met (if threshold > 0)</li>
 *   <li>Stuck detected (same output N times)</li>
 *   <li>Cost limit exceeded</li>
 * </ol>
 *
 * @param maxTurns maximum number of turns (LLM calls) allowed
 * @param timeout maximum duration for the entire loop
 * @param scoreThreshold score threshold for early termination (0.0-1.0)
 * @param stuckThreshold number of identical outputs before detecting stuck
 * @param costLimit maximum cost in dollars before termination
 * @param workingDirectory workspace directory for file operations
 * @param jury optional spring-ai-agents Jury for evaluation
 * @param evaluateEveryNTurns evaluate with jury every N turns (0 = only at end)
 * @param tools list of available tool names
 * @param finishToolName name of the finish tool (default: complete_task)
 */
public record TurnLimitedConfig(
        int maxTurns,
        Duration timeout,
        double scoreThreshold,
        int stuckThreshold,
        double costLimit,
        Path workingDirectory,
        Optional<Jury> jury,
        int evaluateEveryNTurns,
        List<String> tools,
        String finishToolName
) {
    /**
     * Compact constructor with validation.
     * Defaults are applied in the Builder, not here - validation only.
     */
    public TurnLimitedConfig {
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns must be positive");
        }
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        if (scoreThreshold < 0 || scoreThreshold > 1) {
            throw new IllegalArgumentException("scoreThreshold must be between 0 and 1");
        }
        if (stuckThreshold < 0) {
            throw new IllegalArgumentException("stuckThreshold must not be negative");
        }
        if (costLimit < 0) {
            throw new IllegalArgumentException("costLimit must not be negative");
        }
        if (jury == null) {
            jury = Optional.empty();
        }
        if (evaluateEveryNTurns < 0) {
            throw new IllegalArgumentException("evaluateEveryNTurns must not be negative");
        }
        if (tools == null) {
            tools = List.of();
        } else {
            tools = List.copyOf(tools); // Defensive copy for immutability
        }
        if (finishToolName == null || finishToolName.isBlank()) {
            finishToolName = "complete_task";
        }
    }

    /**
     * Returns a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with values from this config.
     * Useful for creating modified copies.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Builder for {@link TurnLimitedConfig}.
     * All fields have sensible defaults. Only override what you need.
     */
    public static final class Builder {
        private int maxTurns = 50;
        private Duration timeout = Duration.ofMinutes(30);
        private double scoreThreshold = 0.0;
        private int stuckThreshold = 3;
        private double costLimit = Double.MAX_VALUE;
        private Path workingDirectory = Path.of(".");
        private Optional<Jury> jury = Optional.empty();
        private int evaluateEveryNTurns = 0;
        private List<String> tools = new ArrayList<>();
        private String finishToolName = "complete_task";

        /**
         * Creates a new builder with default values.
         */
        Builder() {
        }

        /**
         * Creates a builder initialized from an existing config.
         */
        Builder(TurnLimitedConfig config) {
            this.maxTurns = config.maxTurns();
            this.timeout = config.timeout();
            this.scoreThreshold = config.scoreThreshold();
            this.stuckThreshold = config.stuckThreshold();
            this.costLimit = config.costLimit();
            this.workingDirectory = config.workingDirectory();
            this.jury = config.jury();
            this.evaluateEveryNTurns = config.evaluateEveryNTurns();
            this.tools = new ArrayList<>(config.tools());
            this.finishToolName = config.finishToolName();
        }

        /**
         * Sets the maximum number of turns before termination.
         */
        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /**
         * Sets the timeout duration.
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the score threshold for successful completion (0.0 to 1.0).
         */
        public Builder scoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * Sets the stuck detection threshold (consecutive identical outputs).
         */
        public Builder stuckThreshold(int stuckThreshold) {
            this.stuckThreshold = stuckThreshold;
            return this;
        }

        /**
         * Sets the cost limit in dollars.
         */
        public Builder costLimit(double costLimit) {
            this.costLimit = costLimit;
            return this;
        }

        /**
         * Sets the working directory for file operations.
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Sets the jury for evaluation.
         */
        public Builder jury(Jury jury) {
            this.jury = Optional.ofNullable(jury);
            return this;
        }

        /**
         * Sets how often to evaluate (every N turns).
         */
        public Builder evaluateEveryNTurns(int evaluateEveryNTurns) {
            this.evaluateEveryNTurns = evaluateEveryNTurns;
            return this;
        }

        /**
         * Adds a tool to the available tools list.
         */
        public Builder tool(String tool) {
            this.tools.add(tool);
            return this;
        }

        /**
         * Sets the tools list, replacing any existing tools.
         */
        public Builder tools(List<String> tools) {
            this.tools = new ArrayList<>(tools);
            return this;
        }

        /**
         * Provides access to modify the tools list directly.
         */
        public Builder tools(java.util.function.Consumer<List<String>> toolsConsumer) {
            toolsConsumer.accept(this.tools);
            return this;
        }

        /**
         * Sets the finish tool name.
         */
        public Builder finishToolName(String finishToolName) {
            this.finishToolName = finishToolName;
            return this;
        }

        /**
         * Applies custom configuration via a consumer.
         * Useful for reusable configuration snippets.
         */
        public Builder apply(java.util.function.Consumer<Builder> configurer) {
            configurer.accept(this);
            return this;
        }

        /**
         * Creates a copy of this builder for independent modification.
         */
        public Builder copy() {
            Builder copy = new Builder();
            copy.maxTurns = this.maxTurns;
            copy.timeout = this.timeout;
            copy.scoreThreshold = this.scoreThreshold;
            copy.stuckThreshold = this.stuckThreshold;
            copy.costLimit = this.costLimit;
            copy.workingDirectory = this.workingDirectory;
            copy.jury = this.jury;
            copy.evaluateEveryNTurns = this.evaluateEveryNTurns;
            copy.tools = new ArrayList<>(this.tools);
            copy.finishToolName = this.finishToolName;
            return copy;
        }

        /**
         * Builds the configuration.
         */
        public TurnLimitedConfig build() {
            return new TurnLimitedConfig(
                    maxTurns, timeout, scoreThreshold, stuckThreshold, costLimit,
                    workingDirectory, jury, evaluateEveryNTurns, tools, finishToolName
            );
        }
    }
}
