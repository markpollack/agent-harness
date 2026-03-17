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
package io.github.markpollack.harness.flows;

/**
 * The core unit of work in an {@link AgentFlow}.
 * <p>
 * {@code AgentStep} is a typed, composable function from {@code I} to {@code O}.
 * All step implementations — plain Java functions, LLM calls, agentic loops,
 * and graph compositions — adapt to this interface. The {@link AgentFlow}
 * combinators are implementation-agnostic: they take {@code AgentStep} and
 * do not care whether the step calls an LLM, runs a test suite, or posts to GitHub.
 * <p>
 * Step implementations:
 * <ul>
 *   <li>{@link io.github.markpollack.harness.flows.steps.Step} — plain Java function, no LLM</li>
 *   <li>{@link io.github.markpollack.harness.flows.steps.ClaudeStep} — full agentic loop via Claude CLI</li>
 *   <li>{@link io.github.markpollack.harness.flows.steps.ChatClientStep} — single structured LLM call via spring-ai</li>
 *   <li>{@link io.github.markpollack.harness.flows.steps.AgentClientStep} — via AgentClient abstraction</li>
 *   <li>{@link io.github.markpollack.harness.flows.steps.GraphStep} — wraps a GraphCompositionStrategy</li>
 * </ul>
 *
 * @param <I> the input type
 * @param <O> the output type
 */
@FunctionalInterface
public interface AgentStep<I, O> {

    /**
     * Executes this step with the given context and input.
     *
     * @param ctx   the shared execution context for this flow run
     * @param input the input to this step (typically the output of the previous step)
     * @return the output of this step
     */
    O execute(AgentContext ctx, I input);
}
