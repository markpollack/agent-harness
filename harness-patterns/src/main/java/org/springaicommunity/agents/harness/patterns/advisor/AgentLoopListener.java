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
package org.springaicommunity.agents.harness.patterns.advisor;

import org.springaicommunity.agents.harness.core.LoopState;
import org.springaicommunity.agents.harness.core.TerminationReason;

/**
 * Listener for {@link AgentLoopAdvisor} events.
 * <p>
 * Implement this interface to receive notifications about loop progress,
 * useful for:
 * <ul>
 *   <li>TUI updates (spinners, status displays)</li>
 *   <li>Logging and metrics collection</li>
 *   <li>Benchmark progress tracking</li>
 *   <li>Debugging agent behavior</li>
 * </ul>
 * <p>
 * All methods have default no-op implementations, so you only need to
 * override the ones you're interested in.
 * <p>
 * <strong>Exception Handling:</strong> Listeners should not throw exceptions.
 * If an exception is thrown, it will be logged and swallowed to prevent
 * disrupting the agent loop.
 */
public interface AgentLoopListener {

    /**
     * Called when the loop starts processing a new request.
     *
     * @param runId unique identifier for this run
     * @param userMessage the user's input message
     */
    default void onLoopStarted(String runId, String userMessage) {
    }

    /**
     * Called at the start of each turn (before LLM call).
     *
     * @param runId unique identifier for this run
     * @param turn the current turn number (0-indexed)
     */
    default void onTurnStarted(String runId, int turn) {
    }

    /**
     * Called after each turn completes.
     *
     * @param runId unique identifier for this run
     * @param turn the completed turn number
     * @param reason termination reason if this turn caused termination, null otherwise
     */
    default void onTurnCompleted(String runId, int turn, TerminationReason reason) {
    }

    /**
     * Called when the loop completes successfully (no tool calls remaining).
     *
     * @param runId unique identifier for this run
     * @param finalState the final loop state
     * @param reason the reason for termination
     */
    default void onLoopCompleted(String runId, LoopState finalState, TerminationReason reason) {
    }

    /**
     * Called when the loop fails with an exception.
     *
     * @param runId unique identifier for this run
     * @param state the loop state at time of failure
     * @param error the exception that caused the failure
     */
    default void onLoopFailed(String runId, LoopState state, Throwable error) {
    }
}
