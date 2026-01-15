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
package org.springaicommunity.agents.harness.agents.mini;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Iterator;
import java.util.List;

/**
 * A deterministic ChatModel for testing that returns predefined responses.
 * <p>
 * Inspired by mini-swe-agent's DeterministicModel, this allows testing
 * agent behavior without making real LLM calls.
 */
public class DeterministicChatModel implements ChatModel {

    private final Iterator<String> outputIterator;
    private int callCount = 0;
    private double totalCost = 0.0;
    private final double costPerCall;
    private final int tokensPerCall;

    /**
     * Create a DeterministicChatModel that returns the given outputs in order.
     *
     * @param outputs The responses to return (in order)
     */
    public DeterministicChatModel(List<String> outputs) {
        this(outputs, 1.0, 100);
    }

    /**
     * Create a DeterministicChatModel with custom cost and token settings.
     *
     * @param outputs The responses to return (in order)
     * @param costPerCall Cost to add per call (for testing cost limits)
     * @param tokensPerCall Tokens to report per call
     */
    public DeterministicChatModel(List<String> outputs, double costPerCall, int tokensPerCall) {
        this.outputIterator = outputs.iterator();
        this.costPerCall = costPerCall;
        this.tokensPerCall = tokensPerCall;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        callCount++;
        totalCost += costPerCall;

        if (!outputIterator.hasNext()) {
            throw new IllegalStateException(
                    "DeterministicChatModel exhausted all outputs after " + callCount + " calls");
        }

        String content = outputIterator.next();
        AssistantMessage message = new AssistantMessage(content);
        Generation generation = new Generation(message);

        // Create usage metadata
        Usage usage = new TestUsage(tokensPerCall, tokensPerCall, tokensPerCall * 2);

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(usage)
                .build();

        return ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(metadata)
                .build();
    }

    /**
     * @return Number of times call() was invoked
     */
    public int getCallCount() {
        return callCount;
    }

    /**
     * @return Total accumulated cost
     */
    public double getTotalCost() {
        return totalCost;
    }

    /**
     * Simple Usage implementation for testing.
     */
    private record TestUsage(
            Integer promptTokens,
            Integer generationTokens,
            Integer totalTokens
    ) implements Usage {
        @Override
        public Integer getPromptTokens() {
            return promptTokens != null ? promptTokens : 0;
        }

        @Override
        public Integer getCompletionTokens() {
            return generationTokens != null ? generationTokens : 0;
        }

        @Override
        public Integer getTotalTokens() {
            return totalTokens != null ? totalTokens : 0;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
