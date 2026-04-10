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

import io.github.markpollack.workflow.core.AgentContext;

/**
 * Abstraction over any agent backend that accepts a prompt and returns text.
 * <p>
 * Use {@link AgentClientStep} when the invocation goes through this abstraction
 * rather than directly through a specific client like {@link ClaudeSyncClient}.
 * This is the right choice when the client is injected (e.g., from a Spring context)
 * or when you need to swap implementations (e.g., testing with a mock agent).
 *
 * <pre>{@code
 * AgentClientStep.of(agentClient, "prompt: {input}")
 * }</pre>
 *
 * @see AgentClientStep
 * @see ClaudeSyncClient
 */
@FunctionalInterface
public interface AgentClient {

    /**
     * Executes the agent with the given prompt and context.
     *
     * @param prompt the fully resolved prompt (template variables already substituted)
     * @param ctx    the shared execution context for this flow run
     * @return the agent's text response
     */
    String execute(String prompt, AgentContext ctx);
}
