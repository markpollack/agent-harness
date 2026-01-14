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

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * A ToolCallAdvisor that enforces a maximum number of turns (LLM call + tool execution cycles).
 * <p>
 * Spring AI's ToolCallAdvisor runs its internal tool loop until the LLM stops calling tools.
 * This subclass adds turn limiting by counting iterations and throwing {@link TurnLimitExceededException}
 * when the limit is reached.
 * <p>
 * Usage:
 * <pre>{@code
 * var advisor = TurnLimitedToolCallAdvisor.builder()
 *     .maxTurns(10)
 *     .toolCallingManager(manager)
 *     .build();
 *
 * var chatClient = ChatClient.builder(model)
 *     .defaultAdvisors(advisor)
 *     .build();
 * }</pre>
 */
public class TurnLimitedToolCallAdvisor extends ToolCallAdvisor {

    private final int maxTurns;
    private final ThreadLocal<Integer> turnCount = ThreadLocal.withInitial(() -> 0);

    protected TurnLimitedToolCallAdvisor(ToolCallingManager toolCallingManager, int advisorOrder,
                                          boolean conversationHistoryEnabled, int maxTurns) {
        super(toolCallingManager, advisorOrder, conversationHistoryEnabled);
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be at least 1, got: " + maxTurns);
        }
        this.maxTurns = maxTurns;
    }

    @Override
    public String getName() {
        return "Turn Limited Tool Calling Advisor";
    }

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest chatClientRequest,
                                                  CallAdvisorChain callAdvisorChain) {
        turnCount.set(0);  // Reset counter for new run
        return super.doInitializeLoop(chatClientRequest, callAdvisorChain);
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse chatClientResponse,
                                              CallAdvisorChain callAdvisorChain) {
        int current = turnCount.get() + 1;
        turnCount.set(current);

        if (current > maxTurns) {
            throw new TurnLimitExceededException(maxTurns, current, chatClientResponse);
        }

        return super.doAfterCall(chatClientResponse, callAdvisorChain);
    }

    /**
     * Get the maximum number of turns allowed.
     */
    public int getMaxTurns() {
        return maxTurns;
    }

    /**
     * Get the current turn count (for the current thread).
     * Useful for debugging and testing.
     */
    public int getCurrentTurnCount() {
        return turnCount.get();
    }

    /**
     * Creates a new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for TurnLimitedToolCallAdvisor.
     */
    public static class Builder extends ToolCallAdvisor.Builder<Builder> {

        private int maxTurns = 10;

        protected Builder() {
        }

        /**
         * Set the maximum number of turns (LLM call + tool execution cycles) allowed.
         * Default is 10.
         *
         * @param maxTurns the maximum turns, must be at least 1
         * @return this builder
         */
        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public TurnLimitedToolCallAdvisor build() {
            return new TurnLimitedToolCallAdvisor(
                    getToolCallingManager(),
                    getAdvisorOrder(),
                    true,  // conversationHistoryEnabled
                    maxTurns
            );
        }
    }
}
