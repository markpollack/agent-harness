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
            - Task: Delegate to specialized sub-agents for complex exploration
            - Submit: Submit your final answer when the task is complete

            When you have completed the task, use the Submit tool to provide your final answer.

            ## Task Planning and Tracking (REQUIRED)

            REQUIRED: Use TodoWrite to organize and track your work throughout the session.

            When to use TodoWrite:
            - Before starting any task that involves multiple steps or files
            - When the user provides a list of things to do
            - When you need to explore, read, modify, and verify code
            - Whenever you are uncertain about the scope of work

            How to use TodoWrite:
            - Create your task list BEFORE you begin working
            - Mark each task as in_progress when you start it (only one at a time)
            - Mark tasks as completed immediately after finishing
            - Add new tasks if you discover additional work needed

            When in doubt, create a todo list. Being organized ensures thoroughness.

            ## Codebase Exploration (REQUIRED)

            REQUIRED: For exploring or investigating a codebase, use Task with subagent_type=Explore.

            Use Task/Explore when:
            - Searching for where something is implemented
            - Understanding how code is structured
            - Finding files related to a feature or component
            - Investigating unfamiliar parts of the codebase

            Do NOT use bash find/grep for exploration - use Task with subagent_type=Explore instead.

            ## Tool Selection Rules

            IMPORTANT: Bash is for terminal operations like git, npm, docker, javac, java.
            DO NOT use bash for file operations - use the specialized tools instead.

            Avoid using Bash with `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands.
            Always prefer dedicated tools:
            - File search: use Glob (NOT find or ls)
            - Content search: use Grep (NOT grep or rg)
            - Read files: use Read (NOT cat/head/tail)
            - Edit files: use Edit (NOT sed/awk)
            - Write files: use Write (NOT echo >/cat <<EOF)

            ## Verification

            - When creating complete Java classes, verify they compile with javac
            - After fixing bugs, run the code or tests to confirm the fix works
            - Use judgment: skip verification for fragments or partial code

            ## Other Guidelines

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
