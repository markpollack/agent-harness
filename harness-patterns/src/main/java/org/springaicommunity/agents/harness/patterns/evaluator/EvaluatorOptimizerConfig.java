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
package org.springaicommunity.agents.harness.patterns.evaluator;

import org.springaicommunity.judge.jury.Jury;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Configuration for Evaluator-Optimizer loop pattern (Reflexion).
 * <p>
 * This pattern is used by Reflexion, mcp-agent, and sdk-sync-agent.
 * Each iteration has three phases:
 * <ol>
 *   <li>Actor (Generator): Produces output based on input and reflection</li>
 *   <li>Evaluator (Jury): Judges the output quality</li>
 *   <li>Reflector (Optimizer): Produces feedback for next iteration</li>
 * </ol>
 *
 * <p>Termination conditions:
 * <ul>
 *   <li>Max trials/iterations reached</li>
 *   <li>Score threshold met (jury passes)</li>
 *   <li>No improvement detected (stuck)</li>
 *   <li>Timeout exceeded</li>
 *   <li>Finish tool called</li>
 * </ul>
 *
 * @param maxTrials maximum number of generate-evaluate-reflect cycles
 * @param timeout maximum duration for entire optimization process
 * @param scoreThreshold score at which to consider task complete (0.0-1.0)
 * @param requirePass if true, requires jury pass; if false, uses scoreThreshold
 * @param stuckThreshold number of trials without improvement before giving up
 * @param improvementDelta minimum score improvement to not be considered stuck
 * @param workingDirectory workspace directory for file operations and judges
 * @param jury optional spring-ai-agents Jury for evaluation
 * @param reflectorPrompt system prompt for the reflector/optimizer agent
 * @param tools list of available tool names for actor
 * @param finishToolName name of the finish tool
 */
public record EvaluatorOptimizerConfig(
        int maxTrials,
        Duration timeout,
        double scoreThreshold,
        boolean requirePass,
        int stuckThreshold,
        double improvementDelta,
        Path workingDirectory,
        Optional<Jury> jury,
        String reflectorPrompt,
        List<String> tools,
        String finishToolName
) {
    public EvaluatorOptimizerConfig {
        if (maxTrials <= 0) {
            throw new IllegalArgumentException("maxTrials must be positive");
        }
        if (timeout == null) {
            timeout = Duration.ofMinutes(60);
        }
        if (scoreThreshold < 0 || scoreThreshold > 1) {
            throw new IllegalArgumentException("scoreThreshold must be between 0 and 1");
        }
        if (stuckThreshold < 0) {
            stuckThreshold = 3;
        }
        if (improvementDelta < 0) {
            improvementDelta = 0.01;
        }
        if (jury == null) {
            jury = Optional.empty();
        }
        if (reflectorPrompt == null || reflectorPrompt.isBlank()) {
            reflectorPrompt = """
                    You are a reflection agent. Analyze the previous attempt and its evaluation.
                    Provide specific, actionable feedback to improve the next attempt.
                    Focus on what went wrong and how to fix it.
                    """;
        }
        if (tools == null) {
            tools = List.of();
        }
        if (finishToolName == null || finishToolName.isBlank()) {
            finishToolName = "complete_task";
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxTrials = 10;
        private Duration timeout = Duration.ofMinutes(60);
        private double scoreThreshold = 0.8;
        private boolean requirePass = false;
        private int stuckThreshold = 3;
        private double improvementDelta = 0.01;
        private Path workingDirectory;
        private Optional<Jury> jury = Optional.empty();
        private String reflectorPrompt = "";
        private List<String> tools = List.of();
        private String finishToolName = "complete_task";

        public Builder maxTrials(int maxTrials) {
            this.maxTrials = maxTrials;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder scoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        public Builder requirePass(boolean requirePass) {
            this.requirePass = requirePass;
            return this;
        }

        public Builder stuckThreshold(int stuckThreshold) {
            this.stuckThreshold = stuckThreshold;
            return this;
        }

        public Builder improvementDelta(double improvementDelta) {
            this.improvementDelta = improvementDelta;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder jury(Jury jury) {
            this.jury = Optional.ofNullable(jury);
            return this;
        }

        public Builder reflectorPrompt(String reflectorPrompt) {
            this.reflectorPrompt = reflectorPrompt;
            return this;
        }

        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder finishToolName(String finishToolName) {
            this.finishToolName = finishToolName;
            return this;
        }

        public EvaluatorOptimizerConfig build() {
            return new EvaluatorOptimizerConfig(
                    maxTrials, timeout, scoreThreshold, requirePass, stuckThreshold,
                    improvementDelta, workingDirectory, jury, reflectorPrompt, tools, finishToolName
            );
        }
    }
}
