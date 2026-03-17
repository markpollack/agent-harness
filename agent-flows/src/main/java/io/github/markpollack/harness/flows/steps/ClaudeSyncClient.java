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
package io.github.markpollack.harness.flows.steps;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over the Claude CLI agentic execution engine used by {@link ClaudeStep}.
 * <p>
 * The default implementation (used when no client is provided) invokes the {@code claude}
 * CLI as a subprocess. Inject a custom implementation for testing, mocking, or when
 * integrating with a {@code claude-agent-sdk-java} client library.
 *
 * <pre>{@code
 * // For testing: inject a mock
 * ClaudeStep.of("analyze: {input}")
 *     .withClient((prompt, cwd, mcp, mode) -> "mock analysis result")
 * }</pre>
 */
@FunctionalInterface
public interface ClaudeSyncClient {

    /**
     * Runs the Claude agentic loop with the given prompt and configuration.
     * <p>
     * Blocks until Claude finishes (decides it is done). Returns the final
     * text output from the session.
     *
     * @param resolvedPrompt the prompt with all template variables already substituted
     * @param workingDirectory the working directory for the Claude session, or {@code null}
     * @param mcpConfigs  MCP server configurations available to Claude, may be empty
     * @param permissionMode the permission mode for tool use
     * @return the final text output from the Claude session
     */
    String run(String resolvedPrompt, Path workingDirectory, List<McpConfig> mcpConfigs, PermissionMode permissionMode);
}
