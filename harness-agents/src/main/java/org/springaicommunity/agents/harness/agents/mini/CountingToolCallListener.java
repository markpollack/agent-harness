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

import org.springaicommunity.agents.harness.core.ToolCallListener;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A ToolCallListener that counts tool executions and delegates to another listener.
 * <p>
 * This enables tracking toolCallsExecuted in MiniAgentResult while still
 * getting logging or other behavior from the delegate.
 */
public class CountingToolCallListener implements ToolCallListener {

    private final ToolCallListener delegate;
    private final AtomicInteger toolCallCount = new AtomicInteger(0);

    public CountingToolCallListener() {
        this(new LoggingToolCallListener());
    }

    public CountingToolCallListener(ToolCallListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onToolExecutionStarted(String runId, int turn, AssistantMessage.ToolCall toolCall) {
        toolCallCount.incrementAndGet();
        if (delegate != null) {
            delegate.onToolExecutionStarted(runId, turn, toolCall);
        }
    }

    @Override
    public void onToolExecutionCompleted(String runId, int turn, AssistantMessage.ToolCall toolCall,
                                         String result, Duration duration) {
        if (delegate != null) {
            delegate.onToolExecutionCompleted(runId, turn, toolCall, result, duration);
        }
    }

    @Override
    public void onToolExecutionFailed(String runId, int turn, AssistantMessage.ToolCall toolCall,
                                      Throwable error, Duration duration) {
        if (delegate != null) {
            delegate.onToolExecutionFailed(runId, turn, toolCall, error, duration);
        }
    }

    /** Get the count of tool calls executed. */
    public int getToolCallCount() {
        return toolCallCount.get();
    }

    /** Reset the counter (for reuse). */
    public void reset() {
        toolCallCount.set(0);
    }
}
