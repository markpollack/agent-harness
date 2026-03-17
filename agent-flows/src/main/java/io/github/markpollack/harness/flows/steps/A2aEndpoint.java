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

import java.util.Objects;

/**
 * An Agent-to-Agent (A2A) peer endpoint that a {@link ClaudeStep} can delegate
 * sub-tasks to during its agentic loop.
 * <p>
 * A2A endpoints are configured via the Claude CLI and allow Claude to call peer
 * agents (e.g., a dedicated security reviewer agent or docs writer agent) during
 * its tool-calling loop.
 *
 * <pre>{@code
 * ClaudeStep.of("implement the feature: {input}")
 *     .withA2a(A2aEndpoint.of("security-reviewer", securityAgentUrl))
 *     .withA2a(A2aEndpoint.of("docs-writer", docsAgentUrl))
 * }</pre>
 */
public record A2aEndpoint(String name, String url) {

    public A2aEndpoint {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(url, "url must not be null");
    }

    /**
     * Creates an A2A peer agent endpoint.
     *
     * @param name the logical name for this peer agent
     * @param url  the URL at which the peer agent is reachable
     * @return a new A2aEndpoint
     */
    public static A2aEndpoint of(String name, String url) {
        return new A2aEndpoint(name, url);
    }
}
