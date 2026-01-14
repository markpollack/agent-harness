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

import org.springframework.ai.chat.client.ChatClientResponse;

/**
 * Exception thrown when the turn limit is exceeded in a TurnLimitedToolCallAdvisor.
 * <p>
 * This exception carries the partial results from the last response, allowing
 * callers to gracefully handle turn limit situations and extract partial output.
 */
public class TurnLimitExceededException extends RuntimeException {

    private final int maxTurns;
    private final int actualTurns;
    private final ChatClientResponse lastResponse;

    public TurnLimitExceededException(int maxTurns, int actualTurns, ChatClientResponse lastResponse) {
        super("Turn limit exceeded: %d/%d turns".formatted(actualTurns, maxTurns));
        this.maxTurns = maxTurns;
        this.actualTurns = actualTurns;
        this.lastResponse = lastResponse;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public int getActualTurns() {
        return actualTurns;
    }

    public ChatClientResponse getLastResponse() {
        return lastResponse;
    }

    /**
     * Extract partial output text from the last response, if available.
     */
    public String getPartialOutput() {
        if (lastResponse == null || lastResponse.chatResponse() == null) {
            return null;
        }
        var result = lastResponse.chatResponse().getResult();
        if (result == null || result.getOutput() == null) {
            return null;
        }
        return result.getOutput().getText();
    }
}
