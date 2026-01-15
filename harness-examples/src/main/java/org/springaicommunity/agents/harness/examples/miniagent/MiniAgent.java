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

import io.micrometer.observation.ObservationRegistry;
import org.springaicommunity.agents.harness.core.ToolCallListener;
import org.springaicommunity.agents.harness.patterns.advisor.AgentLoopAdvisor;
import org.springaicommunity.agents.harness.patterns.advisor.AgentLoopTerminatedException;
import org.springaicommunity.agents.harness.patterns.observation.ToolCallObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.DefaultToolCallingManager;

import java.util.Arrays;
import java.util.List;

/**
 * MiniAgent - A minimal SWE agent leveraging Spring AI's built-in agent loop.
 * <p>
 * Spring AI's ChatClient + ToolCallAdvisor handles the entire tool execution loop.
 * This agent adds: tools, observability wiring, and a simple API.
 * <p>
 * Under 100 lines - terser than Python mini-swe-agent because Spring AI does the heavy lifting.
 */
public class MiniAgent {

    private static final Logger log = LoggerFactory.getLogger(MiniAgent.class);

    private final MiniAgentConfig config;
    private final ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final ToolCallObservationHandler observationHandler;
    private final CountingToolCallListener countingListener;

    public MiniAgent(MiniAgentConfig config, ChatModel model) {
        this(config, model, new LoggingToolCallListener());
    }

    public MiniAgent(MiniAgentConfig config, ChatModel model, ToolCallListener listener) {
        this.config = config;

        // Create tools
        var toolsObj = new MiniAgentTools(config.workingDirectory(), config.commandTimeout());
        this.tools = Arrays.asList(ToolCallbacks.from(toolsObj));

        // Wrap listener in counting listener for toolCallsExecuted tracking
        this.countingListener = new CountingToolCallListener(listener);

        // Wire observability: ObservationRegistry → ToolCallObservationHandler → ToolCallListener
        this.observationHandler = ToolCallObservationHandler.of(countingListener);
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(observationHandler);

        // Create ChatClient with AgentLoopAdvisor - unified loop control
        var toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(registry)
                .build();
        var toolCallAdvisor = AgentLoopAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .maxTurns(config.maxTurns())
                .build();
        this.chatClient = ChatClient.builder(model)
                .defaultAdvisors(toolCallAdvisor)
                .build();
    }

    /** Run the agent with the given task. */
    public MiniAgentResult run(String task) {
        log.info("MiniAgent starting: {}", truncate(task, 80));
        countingListener.reset();  // Reset counter for this run
        observationHandler.setContext("mini-agent", 1);

        try {
            ChatResponse response = chatClient.prompt()
                    .user(config.systemPrompt() + "\n\nTask: " + task)
                    .toolCallbacks(tools)
                    .call()
                    .chatResponse();

            long tokens = extractTokens(response);
            String output = extractText(response);
            int toolCalls = countingListener.getToolCallCount();
            log.info("MiniAgent completed: {} tokens, {} tool calls", tokens, toolCalls);

            return new MiniAgentResult("COMPLETED", output, 1, toolCalls, tokens, tokens * 0.000006);

        } catch (AgentLoopTerminatedException e) {
            var state = e.getState();
            log.warn("MiniAgent terminated: {} at turn {}", e.getReason(), state != null ? state.currentTurn() : 0);
            int toolCalls = countingListener.getToolCallCount();
            String status = switch (e.getReason()) {
                case MAX_TURNS_REACHED -> "TURN_LIMIT_REACHED";
                case TIMEOUT -> "TIMEOUT";
                case COST_LIMIT_EXCEEDED -> "COST_LIMIT_EXCEEDED";
                case STUCK_DETECTED -> "STUCK";
                case EXTERNAL_SIGNAL -> "ABORTED";
                default -> "TERMINATED";
            };
            return new MiniAgentResult(status, e.getPartialOutput(),
                    state != null ? state.currentTurn() : 0, toolCalls,
                    state != null ? state.totalTokensUsed() : 0,
                    state != null ? state.estimatedCost() : 0.0);
        }
    }

    private long extractTokens(ChatResponse r) {
        if (r == null || r.getMetadata() == null || r.getMetadata().getUsage() == null) return 0;
        var t = r.getMetadata().getUsage().getTotalTokens();
        return t != null ? t : 0;
    }

    private String extractText(ChatResponse r) {
        return r != null && r.getResult() != null ? r.getResult().getOutput().getText() : null;
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Result of a MiniAgent execution.
     *
     * @param status Status of the execution (COMPLETED, TURN_LIMIT_REACHED, FAILED)
     * @param output The agent's final output (may be partial if turn limit reached)
     * @param turnsCompleted Number of turns (LLM call + tool execution cycles) completed
     * @param toolCallsExecuted Number of tool calls executed across all turns
     * @param totalTokens Total tokens used
     * @param estimatedCost Estimated cost in dollars
     */
    public record MiniAgentResult(
            String status,
            String output,
            int turnsCompleted,
            int toolCallsExecuted,
            long totalTokens,
            double estimatedCost
    ) {
        public boolean isSuccess() { return "COMPLETED".equals(status); }
        public boolean isFailure() { return "FAILED".equals(status); }
        public boolean isTurnLimitReached() { return "TURN_LIMIT_REACHED".equals(status); }
    }
}
