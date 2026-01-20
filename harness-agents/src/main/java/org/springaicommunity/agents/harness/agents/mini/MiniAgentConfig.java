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
package org.springaicommunity.agents.harness.agents.mini;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Configuration for MiniAgent - a minimal SWE agent implementation.
 * <p>
 * Mirrors the configuration options from mini-swe-agent's default.yaml.
 */
public record MiniAgentConfig(
        String systemPrompt,
        int maxTurns,
        double costLimit,
        Duration commandTimeout,
        Path workingDirectory
) {
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are an autonomous AI assistant that solves software engineering tasks.

            You have access to the following tools:
            - Read: Read file contents (use absolute paths)
            - Write: Create or overwrite files (use absolute paths)
            - Edit: Make targeted edits to existing files
            - LS: List directory contents
            - Bash: Execute shell commands
            - Glob: Find files by pattern
            - Grep: Search file contents
            - TodoWrite: Track progress on multi-step tasks
            - Submit: Submit your final answer when the task is complete

            When you have completed the task, use the Submit tool to provide your final answer.

            Important:
            - Use Read/Write/Edit for file operations, NOT bash echo/cat
            - Use Glob for file discovery, NOT bash ls/find
            - Use Grep for content search, NOT bash grep/rg
            - Use TodoWrite for tasks with 3+ steps to track progress
            - All file paths must be absolute paths
            - Execute one operation at a time
            - Check output before proceeding
            - If an operation fails, analyze the error and try a different approach
            """;

    private static final int DEFAULT_MAX_TURNS = 20;
    private static final double DEFAULT_COST_LIMIT = 1.0;
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    public MiniAgentConfig {
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns must be positive");
        }
        if (costLimit <= 0) {
            throw new IllegalArgumentException("costLimit must be positive");
        }
        if (commandTimeout == null || commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        if (workingDirectory == null) {
            throw new IllegalArgumentException("workingDirectory cannot be null");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .systemPrompt(this.systemPrompt)
                .maxTurns(this.maxTurns)
                .costLimit(this.costLimit)
                .commandTimeout(this.commandTimeout)
                .workingDirectory(this.workingDirectory);
    }

    public MiniAgentConfig apply(Consumer<Builder> customizer) {
        Builder builder = toBuilder();
        customizer.accept(builder);
        return builder.build();
    }

    public static final class Builder {
        private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        private int maxTurns = DEFAULT_MAX_TURNS;
        private double costLimit = DEFAULT_COST_LIMIT;
        private Duration commandTimeout = DEFAULT_COMMAND_TIMEOUT;
        private Path workingDirectory;

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder costLimit(double costLimit) {
            this.costLimit = costLimit;
            return this;
        }

        public Builder commandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public MiniAgentConfig build() {
            if (workingDirectory == null) {
                workingDirectory = Path.of(System.getProperty("user.dir"));
            }
            return new MiniAgentConfig(
                    systemPrompt,
                    maxTurns,
                    costLimit,
                    commandTimeout,
                    workingDirectory
            );
        }
    }
}
