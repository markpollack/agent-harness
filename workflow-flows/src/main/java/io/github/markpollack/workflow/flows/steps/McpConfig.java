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
package io.github.markpollack.workflow.flows.steps;

import java.util.Objects;

/**
 * Configuration for an MCP (Model Context Protocol) server made available to a
 * {@link ClaudeStep} during its agentic loop.
 * <p>
 * The {@code serverConfig} object is serialized to JSON and passed to the Claude CLI
 * via {@code --mcp-config}. It should be a {@code Map<String, Object>} or any
 * Jackson-serializable type describing the MCP server (command, args, env, etc.).
 *
 * <pre>{@code
 * McpConfig.of("filesystem", Map.of(
 *     "command", "npx",
 *     "args", List.of("-y", "@modelcontextprotocol/server-filesystem", "/path/to/repo")
 * ))
 * }</pre>
 */
public record McpConfig(String name, Object serverConfig) {

    public McpConfig {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(serverConfig, "serverConfig must not be null");
    }

    /**
     * Creates an MCP server configuration.
     *
     * @param name         the logical name for this MCP server
     * @param serverConfig the server configuration (Map or any Jackson-serializable object)
     * @return a new McpConfig
     */
    public static McpConfig of(String name, Object serverConfig) {
        return new McpConfig(name, serverConfig);
    }
}
