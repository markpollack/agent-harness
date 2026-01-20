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

import io.micrometer.observation.ObservationRegistry;
import org.springaicommunity.agents.harness.callback.AgentCallback;
import org.springaicommunity.agents.harness.core.ToolCallListener;
import org.springaicommunity.agents.harness.patterns.advisor.AgentLoopAdvisor;
import org.springaicommunity.agents.harness.patterns.advisor.AgentLoopListener;
import org.springaicommunity.agents.harness.patterns.advisor.AgentLoopTerminatedException;
import org.springaicommunity.agents.harness.patterns.observation.ToolCallObservationHandler;
import org.springaicommunity.agents.harness.core.LoopState;
import org.springaicommunity.agents.harness.core.TerminationReason;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.subagent.claude.ClaudeSubagentExecutor;
import org.springaicommunity.agents.harness.tools.BashTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.DefaultToolCallingManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * MiniAgent - A minimal SWE agent leveraging Spring AI's built-in agent loop.
 * <p>
 * Spring AI's ChatClient + ToolCallAdvisor handles the entire tool execution loop.
 * This agent adds: tools, observability wiring, session memory, and a simple API.
 * <p>
 * Features:
 * <ul>
 *   <li><strong>Session memory</strong>: Optional multi-turn conversation support</li>
 *   <li><strong>Interactive mode</strong>: Enables AskUserQuestionTool for human-in-the-loop</li>
 *   <li><strong>Callbacks</strong>: AgentCallback for TUI integration</li>
 * </ul>
 *
 * @see MiniAgentConfig for configuration options
 */
public class MiniAgent {

    private static final Logger log = LoggerFactory.getLogger(MiniAgent.class);

    private final MiniAgentConfig config;
    private final ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final ToolCallObservationHandler observationHandler;
    private final CountingToolCallListener countingListener;
    private final ChatMemory sessionMemory;
    private final boolean interactive;
    private final String conversationId;

    private MiniAgent(Builder builder) {
        this.config = builder.config;
        this.sessionMemory = builder.sessionMemory;
        this.interactive = builder.interactive;
        this.conversationId = builder.conversationId != null ? builder.conversationId : "default";

        // Create tools - mix of spring-ai-agent-utils and harness-tools
        // Use harness-tools BashTool instead of ShellTools to avoid overly verbose tool descriptions
        // Pass workingDirectory to tools that support it so they operate within the sandbox context

        // Tools with @Tool annotated methods - convert via ToolCallbacks.from()
        List<Object> annotatedToolObjects = new ArrayList<>();
        annotatedToolObjects.add(FileSystemTools.builder().build());
        annotatedToolObjects.add(new BashTool(config.workingDirectory(), config.commandTimeout()));
        annotatedToolObjects.add(GlobTool.builder()
                .workingDirectory(config.workingDirectory())
                .build());
        annotatedToolObjects.add(GrepTool.builder()
                .workingDirectory(config.workingDirectory())
                .build());
        annotatedToolObjects.add(new SubmitTool());
        annotatedToolObjects.add(TodoWriteTool.builder().build());

        // Add AskUserQuestionTool if interactive mode and callback provided
        if (interactive && builder.agentCallback != null) {
            annotatedToolObjects.add(AskUserQuestionTool.builder()
                    .questionHandler(questions -> builder.agentCallback.onQuestion(questions))
                    .build());
        }

        // Tools that directly implement ToolCallback - add directly to callback list
        List<ToolCallback> directCallbacks = new ArrayList<>();

        // Add TaskTool for sub-agent delegation (returns ToolCallback directly)
        var taskRepository = new DefaultTaskRepository();
        var subagentExecutor = new ClaudeSubagentExecutor(
                Map.of("default", ChatClient.builder(builder.model)),
                List.of() // Sub-agents will have access to same tools via their own config
        );
        directCallbacks.add(TaskTool.builder()
                .taskRepository(taskRepository)
                .subagentExecutors(subagentExecutor)
                .build());

        // Convert @Tool annotated objects to ToolCallbacks and merge with direct callbacks
        var annotatedCallbacks = ToolCallbacks.from(annotatedToolObjects.toArray());
        var allCallbacks = new ArrayList<>(Arrays.asList(annotatedCallbacks));
        allCallbacks.addAll(directCallbacks);
        this.tools = List.copyOf(allCallbacks);

        // Wrap listener in counting listener for toolCallsExecuted tracking
        ToolCallListener baseListener = builder.toolCallListener != null
                ? builder.toolCallListener
                : new LoggingToolCallListener();
        this.countingListener = new CountingToolCallListener(baseListener);

        // Wire observability: ObservationRegistry → ToolCallObservationHandler → ToolCallListener
        this.observationHandler = ToolCallObservationHandler.of(countingListener);
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(observationHandler);

        // Create ChatClient with advisors
        var toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(registry)
                .build();

        // Build AgentLoopAdvisor with optional listener bridge
        var advisorBuilder = AgentLoopAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .maxTurns(config.maxTurns());

        if (builder.agentCallback != null) {
            advisorBuilder.listener(new CallbackLoopListener(builder.agentCallback));
        }

        var toolCallAdvisor = advisorBuilder.build();

        // Build ChatClient with optional memory advisor
        // Note: defaultToolContext is required for tools that use ToolContext (e.g., FileSystemTools)
        // tools list already contains ToolCallback instances
        var chatClientBuilder = ChatClient.builder(builder.model)
                .defaultAdvisors(toolCallAdvisor)
                .defaultToolCallbacks(tools.toArray(new ToolCallback[0]))
                .defaultToolContext(Map.of("agentId", "mini-agent"));

        if (sessionMemory != null) {
            var memoryAdvisor = MessageChatMemoryAdvisor.builder(sessionMemory)
                    .conversationId(conversationId)
                    .build();
            chatClientBuilder.defaultAdvisors(memoryAdvisor);
        }

        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Run the agent with the given task (single-task mode).
     * <p>
     * If session memory is configured, the conversation history is preserved
     * across multiple run() calls.
     */
    public MiniAgentResult run(String task) {
        log.info("MiniAgent starting: {}", truncate(task, 80));
        countingListener.reset();
        observationHandler.setContext("mini-agent", 1);

        try {
            // Include working directory in system prompt so LLM uses correct paths
            String systemPromptWithWorkdir = config.systemPrompt() +
                    "\n\nYour working directory is: " + config.workingDirectory().toAbsolutePath();

            ChatResponse response = chatClient.prompt()
                    .system(systemPromptWithWorkdir)
                    .user(task)
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

    /**
     * Chat with the agent (multi-turn interactive mode).
     * <p>
     * Unlike run(), this method is designed for interactive TUI use:
     * - Callbacks are invoked for thinking, tool calls, and responses
     * - Session memory preserves conversation across calls
     * - Questions are routed to the callback for user interaction
     *
     * @param message User message
     * @param callback Callback for events (must be same as builder callback)
     * @return Agent result
     */
    public MiniAgentResult chat(String message, AgentCallback callback) {
        // Note: onThinking is called by CallbackLoopListener.onTurnStarted()
        MiniAgentResult result = run(message);
        if (callback != null) {
            callback.onComplete();
        }
        return result;
    }

    /**
     * Clear session memory, starting a fresh conversation.
     */
    public void clearSession() {
        if (sessionMemory != null) {
            sessionMemory.clear(conversationId);
            log.debug("Session cleared for conversation: {}", conversationId);
        }
    }

    /**
     * Check if session memory is enabled.
     */
    public boolean hasSessionMemory() {
        return sessionMemory != null;
    }

    /**
     * Check if interactive mode is enabled.
     */
    public boolean isInteractive() {
        return interactive;
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

    // --- Static factory methods for backwards compatibility ---

    /**
     * Create a MiniAgent with default configuration.
     * @deprecated Use {@link #builder()} instead
     */
    @Deprecated
    public MiniAgent(MiniAgentConfig config, ChatModel model) {
        this(builder().config(config).model(model));
    }

    /**
     * Create a MiniAgent with a custom tool listener.
     * @deprecated Use {@link #builder()} instead
     */
    @Deprecated
    public MiniAgent(MiniAgentConfig config, ChatModel model, ToolCallListener listener) {
        this(builder().config(config).model(model).toolCallListener(listener));
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private MiniAgentConfig config;
        private ChatModel model;
        private ChatMemory sessionMemory;
        private boolean interactive = false;
        private AgentCallback agentCallback;
        private ToolCallListener toolCallListener;
        private String conversationId;

        private Builder() {}

        /**
         * Set the agent configuration (required).
         */
        public Builder config(MiniAgentConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Set the chat model (required).
         */
        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        /**
         * Enable session memory for multi-turn conversations.
         * <p>
         * If null (default), each run() is independent with no history.
         * If provided, conversation history is preserved across calls.
         */
        public Builder sessionMemory(ChatMemory sessionMemory) {
            this.sessionMemory = sessionMemory;
            return this;
        }

        /**
         * Enable session memory with default in-memory implementation.
         */
        public Builder sessionMemory() {
            this.sessionMemory = MessageWindowChatMemory.builder().build();
            return this;
        }

        /**
         * Enable interactive mode with AskUserQuestionTool.
         * <p>
         * When true and agentCallback is provided, the agent can ask
         * the user questions during execution via onQuestion().
         */
        public Builder interactive(boolean interactive) {
            this.interactive = interactive;
            return this;
        }

        /**
         * Set callback for agent events (TUI integration).
         * <p>
         * Required for interactive mode to handle questions.
         */
        public Builder agentCallback(AgentCallback agentCallback) {
            this.agentCallback = agentCallback;
            return this;
        }

        /**
         * Set callback for tool call events.
         */
        public Builder toolCallListener(ToolCallListener toolCallListener) {
            this.toolCallListener = toolCallListener;
            return this;
        }

        /**
         * Set conversation ID for session memory.
         * <p>
         * Defaults to "default" if not specified.
         */
        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public MiniAgent build() {
            if (config == null) {
                throw new IllegalStateException("config is required");
            }
            if (model == null) {
                throw new IllegalStateException("model is required");
            }
            if (interactive && agentCallback == null) {
                log.warn("Interactive mode enabled but no agentCallback provided - questions will not be handled");
            }
            return new MiniAgent(this);
        }
    }

    /**
     * Bridges AgentLoopListener events to AgentCallback.
     */
    private static class CallbackLoopListener implements AgentLoopListener {
        private final AgentCallback callback;

        CallbackLoopListener(AgentCallback callback) {
            this.callback = callback;
        }

        @Override
        public void onLoopStarted(String runId, String userMessage) {
            // AgentCallback doesn't have a direct equivalent
        }

        @Override
        public void onTurnStarted(String runId, int turn) {
            callback.onThinking();
        }

        @Override
        public void onTurnCompleted(String runId, int turn, TerminationReason reason) {
            // Handled in onLoopCompleted
        }

        @Override
        public void onLoopCompleted(String runId, LoopState state, TerminationReason reason) {
            callback.onComplete();
        }

        @Override
        public void onLoopFailed(String runId, LoopState state, Throwable error) {
            callback.onError(error);
        }
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

    /**
     * Tool for submitting the final answer and completing the task.
     * <p>
     * Using returnDirect=true means the result is returned directly to the user
     * without going back to the model, effectively terminating the agent loop.
     */
    private static class SubmitTool {

        private static final Logger log = LoggerFactory.getLogger(SubmitTool.class);

        @org.springframework.ai.tool.annotation.Tool(
            name = "Submit",
            description = "Submit your final answer when the task is complete. This ends the conversation.",
            returnDirect = true)
        public String submit(
                @org.springframework.ai.tool.annotation.ToolParam(
                    description = "The final answer or result of the task") String answer) {
            log.info("Task submitted with answer: {}", answer != null && answer.length() > 100
                    ? answer.substring(0, 100) + "..." : answer);
            return answer;
        }
    }
}
