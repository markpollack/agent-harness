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
package org.springaicommunity.agents.harness.examples.miniagent;

import org.springaicommunity.agents.harness.core.ToolCallListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;

/**
 * A simple ToolCallListener that logs tool executions at INFO level.
 */
public class LoggingToolCallListener implements ToolCallListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolCallListener.class);

    @Override
    public void onToolExecutionStarted(String runId, int turn, AssistantMessage.ToolCall toolCall) {
        String args = truncate(toolCall.arguments(), 80);
        log.info("  [{}] {}({})", toolCall.name(), args.isEmpty() ? "" : args);
    }

    @Override
    public void onToolExecutionCompleted(String runId, int turn, AssistantMessage.ToolCall toolCall,
                                         String result, Duration duration) {
        String truncatedResult = truncate(result, 100);
        log.info("  [{}] completed in {}ms: {}", toolCall.name(), duration.toMillis(), truncatedResult);
    }

    @Override
    public void onToolExecutionFailed(String runId, int turn, AssistantMessage.ToolCall toolCall,
                                      Throwable error, Duration duration) {
        log.warn("  [{}] failed in {}ms: {}", toolCall.name(), duration.toMillis(), error.getMessage());
    }

    private String truncate(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace("\n", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
