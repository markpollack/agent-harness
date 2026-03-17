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
 * Marker interface for {@link Step} implementations that make LLM calls.
 * <p>
 * Implement this on any step that invokes a language model — whether via the
 * Claude CLI ({@code ClaudeStep}), Spring AI {@code ChatClient} ({@code ChatClientStep}),
 * or a custom agent abstraction ({@code AgentClientStep}). User-defined steps that
 * wrap LLM calls should also implement this for correct {@code NodeType.AGENT} cost
 * tracking in the {@link io.github.markpollack.harness.flows.workflow.WorkflowGraph}.
 * <p>
 * The {@code WorkflowBuilder} detects this marker via {@code instanceof AgentStep} —
 * replacing fragile class-name string matching.
 */
public interface AgentStep {
    // marker — no methods
}
