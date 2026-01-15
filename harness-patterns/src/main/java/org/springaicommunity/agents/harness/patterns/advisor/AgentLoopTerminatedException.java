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
import org.springframework.ai.chat.client.ChatClientResponse;

/**
 * Exception thrown when an agent loop terminates due to a condition being met.
 * <p>
 * This exception is caught by Spring AI's advisor infrastructure and allows
 * callers to gracefully handle various termination scenarios:
 * <ul>
 *   <li>Max turns reached</li>
 *   <li>Timeout exceeded</li>
 *   <li>Cost limit exceeded</li>
 *   <li>Stuck detection (same output repeated)</li>
 *   <li>Abort signal received</li>
 * </ul>
 * <p>
 * The exception carries the partial response and loop state, allowing callers
 * to extract useful information even from incomplete runs.
 */
public class AgentLoopTerminatedException extends RuntimeException {

    private final TerminationReason reason;
    private final LoopState state;
    private final ChatClientResponse partialResponse;

    /**
     * Creates a new termination exception.
     *
     * @param reason the reason for termination
     * @param message human-readable description
     * @param state the loop state at termination
     */
    public AgentLoopTerminatedException(TerminationReason reason, String message, LoopState state) {
        this(reason, message, state, null);
    }

    /**
     * Creates a new termination exception with partial response.
     *
     * @param reason the reason for termination
     * @param message human-readable description
     * @param state the loop state at termination
     * @param partialResponse the last response before termination
     */
    public AgentLoopTerminatedException(TerminationReason reason, String message,
                                         LoopState state, ChatClientResponse partialResponse) {
        super(message);
        this.reason = reason;
        this.state = state;
        this.partialResponse = partialResponse;
    }

    /**
     * Returns the reason for loop termination.
     */
    public TerminationReason getReason() {
        return reason;
    }

    /**
     * Returns the loop state at the time of termination.
     */
    public LoopState getState() {
        return state;
    }

    /**
     * Returns the partial response, if available.
     */
    public ChatClientResponse getPartialResponse() {
        return partialResponse;
    }

    /**
     * Extracts partial output text from the last response, if available.
     */
    public String getPartialOutput() {
        if (partialResponse == null || partialResponse.chatResponse() == null) {
            return null;
        }
        var result = partialResponse.chatResponse().getResult();
        if (result == null || result.getOutput() == null) {
            return null;
        }
        return result.getOutput().getText();
    }

    /**
     * Returns true if this termination represents a successful completion
     * (e.g., jury passed, task completed).
     */
    public boolean isSuccessfulTermination() {
        return reason == TerminationReason.SCORE_THRESHOLD_MET ||
               reason == TerminationReason.FINISH_TOOL_CALLED ||
               reason == TerminationReason.USER_APPROVAL;
    }
}
