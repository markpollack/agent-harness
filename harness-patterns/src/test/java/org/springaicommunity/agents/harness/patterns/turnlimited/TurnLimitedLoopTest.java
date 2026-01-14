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
package org.springaicommunity.agents.harness.patterns.turnlimited;

import org.springaicommunity.agents.harness.core.LoopStatus;
import org.springaicommunity.agents.harness.core.TerminationReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TurnLimitedLoop")
class TurnLimitedLoopTest {

    @Mock
    private ChatModel chatModel;

    @Captor
    private ArgumentCaptor<Prompt> promptCaptor;

    private ChatClient chatClient;
    private TurnLimitedConfig config;
    private List<ToolCallback> tools;

    @BeforeEach
    void setUp() {
        chatClient = ChatClient.builder(chatModel).build();
        config = TurnLimitedConfig.builder()
                .maxTurns(10)
                .timeout(Duration.ofMinutes(5))
                .workingDirectory(Path.of("."))
                .build();
        tools = List.of();
    }

    /**
     * Creates a mock ChatResponse with no tool calls.
     */
    private ChatResponse createResponseWithoutToolCalls(String content) {
        AssistantMessage message = new AssistantMessage(content);
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(message);

        Usage usage = mock(Usage.class);
        when(usage.getTotalTokens()).thenReturn(Integer.valueOf(100));

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse response = ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(metadata)
                .build();

        // Spy to override hasToolCalls
        ChatResponse spy = Mockito.spy(response);
        when(spy.hasToolCalls()).thenReturn(false);

        return spy;
    }

    /**
     * Creates a mock ChatResponse with tool calls.
     */
    private ChatResponse createResponseWithToolCalls(String toolName) {
        var toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", toolName, "{}");

        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();

        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(message);

        Usage usage = mock(Usage.class);
        when(usage.getTotalTokens()).thenReturn(Integer.valueOf(150));

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse response = ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(metadata)
                .build();

        ChatResponse spy = Mockito.spy(response);
        when(spy.hasToolCalls()).thenReturn(true);

        return spy;
    }

    @Nested
    @DisplayName("Termination on no tool calls")
    class NoToolCallsTermination {

        @Test
        @DisplayName("should terminate when response has no tool calls")
        void shouldTerminateWhenNoToolCalls() {
            ChatResponse response = createResponseWithoutToolCalls("Task completed");

            given(chatModel.call(any(Prompt.class))).willReturn(response);

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(config)
                    .build();

            TurnLimitedResult result = loop.execute("Do something", chatClient, tools);

            assertThat(result.status()).isEqualTo(LoopStatus.COMPLETED);
            assertThat(result.reason()).isEqualTo(TerminationReason.FINISH_TOOL_CALLED);
            assertThat(result.turnsCompleted()).isEqualTo(1);
            assertThat(result.output()).isEqualTo("Task completed");
        }
    }

    @Nested
    @DisplayName("Termination on finish tool")
    class FinishToolTermination {

        @Test
        @DisplayName("should terminate when finish tool is called")
        void shouldTerminateWhenFinishToolCalled() {
            ChatResponse response = createResponseWithToolCalls("complete_task");

            given(chatModel.call(any(Prompt.class))).willReturn(response);

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(config)
                    .build();

            TurnLimitedResult result = loop.execute("Do something", chatClient, tools);

            assertThat(result.status()).isEqualTo(LoopStatus.COMPLETED);
            assertThat(result.reason()).isEqualTo(TerminationReason.FINISH_TOOL_CALLED);
            assertThat(result.finishToolCalled()).isTrue();
        }

        @Test
        @DisplayName("should use custom finish tool name")
        void shouldUseCustomFinishToolName() {
            TurnLimitedConfig customConfig = config.toBuilder()
                    .finishToolName("task_done")
                    .build();

            ChatResponse response = createResponseWithToolCalls("task_done");

            given(chatModel.call(any(Prompt.class))).willReturn(response);

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(customConfig)
                    .build();

            TurnLimitedResult result = loop.execute("Do something", chatClient, tools);

            assertThat(result.reason()).isEqualTo(TerminationReason.FINISH_TOOL_CALLED);
        }
    }

    @Nested
    @DisplayName("Termination on max turns")
    class MaxTurnsTermination {

        @Test
        @DisplayName("should terminate when max turns reached")
        void shouldTerminateWhenMaxTurnsReached() {
            TurnLimitedConfig limitedConfig = TurnLimitedConfig.builder()
                    .maxTurns(3)
                    .workingDirectory(Path.of("."))
                    .build();

            // Non-finish tool calls that continue the loop
            ChatResponse response = createResponseWithToolCalls("some_other_tool");

            AtomicInteger callCount = new AtomicInteger(0);
            given(chatModel.call(any(Prompt.class))).willAnswer(inv -> {
                callCount.incrementAndGet();
                return response;
            });

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(limitedConfig)
                    .build();

            TurnLimitedResult result = loop.execute("Do something", chatClient, tools);

            assertThat(result.status()).isEqualTo(LoopStatus.COMPLETED);
            assertThat(result.reason()).isEqualTo(TerminationReason.MAX_TURNS_REACHED);
            assertThat(result.maxTurnsReached()).isTrue();
            assertThat(callCount.get()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Abort signal")
    class AbortSignal {

        @Test
        @DisplayName("should terminate on abort signal")
        void shouldTerminateOnAbortSignal() {
            TurnLimitedConfig multiTurnConfig = TurnLimitedConfig.builder()
                    .maxTurns(100)
                    .workingDirectory(Path.of("."))
                    .build();

            ChatResponse response = createResponseWithToolCalls("some_tool");

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(multiTurnConfig)
                    .build();

            AtomicInteger callCount = new AtomicInteger(0);
            given(chatModel.call(any(Prompt.class))).willAnswer(inv -> {
                if (callCount.incrementAndGet() >= 2) {
                    loop.abort(); // Abort after 2 calls
                }
                return response;
            });

            TurnLimitedResult result = loop.execute("Do something", chatClient, tools);

            assertThat(result.reason()).isEqualTo(TerminationReason.EXTERNAL_SIGNAL);
            assertThat(callCount.get()).isLessThanOrEqualTo(3); // Should stop shortly after abort
        }
    }

    @Nested
    @DisplayName("Token and cost tracking")
    class TokenTracking {

        @Test
        @DisplayName("should track tokens across turns")
        void shouldTrackTokensAcrossTurns() {
            TurnLimitedConfig twoTurnConfig = TurnLimitedConfig.builder()
                    .maxTurns(2)
                    .workingDirectory(Path.of("."))
                    .build();

            // First response with tool call
            ChatResponse response1 = createResponseWithToolCalls("some_tool");
            // Second response without tool calls (terminates)
            ChatResponse response2 = createResponseWithoutToolCalls("Done");

            AtomicInteger callCount = new AtomicInteger(0);
            given(chatModel.call(any(Prompt.class))).willAnswer(inv -> {
                return callCount.incrementAndGet() == 1 ? response1 : response2;
            });

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(twoTurnConfig)
                    .build();

            TurnLimitedResult result = loop.execute("Do something", chatClient, tools);

            // First call: 150 tokens, second call: 100 tokens
            assertThat(result.totalTokens()).isEqualTo(250);
            assertThat(result.estimatedCost()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Listener notifications")
    class ListenerNotifications {

        @Test
        @DisplayName("should notify listeners on loop events")
        void shouldNotifyListenersOnLoopEvents() {
            ChatResponse response = createResponseWithoutToolCalls("Done");
            given(chatModel.call(any(Prompt.class))).willReturn(response);

            List<String> events = new ArrayList<>();

            TurnLimitedLoop.LoopListener listener = new TurnLimitedLoop.LoopListener() {
                @Override
                public void onLoopStarted(String runId, String userMessage) {
                    events.add("started:" + runId);
                }

                @Override
                public void onTurnStarted(String runId, int turn) {
                    events.add("turn-started:" + turn);
                }

                @Override
                public void onTurnCompleted(String runId, int turn, TerminationReason reason) {
                    events.add("turn-completed:" + turn + ":" + (reason != null ? reason.name() : "null"));
                }

                @Override
                public void onLoopCompleted(TurnLimitedResult result) {
                    events.add("completed:" + result.status().name());
                }
            };

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(config)
                    .listener(listener)
                    .build();

            loop.execute("Do something", chatClient, tools);

            assertThat(events).hasSize(4);
            assertThat(events.get(0)).startsWith("started:");
            assertThat(events.get(1)).isEqualTo("turn-started:0");
            assertThat(events.get(2)).startsWith("turn-completed:0:");
            assertThat(events.get(3)).isEqualTo("completed:COMPLETED");
        }
    }

    @Nested
    @DisplayName("Basic execution")
    class BasicExecution {

        @Test
        @DisplayName("should execute successfully")
        void shouldExecuteSuccessfully() {
            ChatResponse response = createResponseWithoutToolCalls("Done");
            given(chatModel.call(any(Prompt.class))).willReturn(response);

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(config)
                    .build();

            TurnLimitedResult result = loop.execute("Do something", chatClient, tools);

            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should return failed result on exception")
        void shouldReturnFailedResultOnException() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("API error"));

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(config)
                    .build();

            TurnLimitedResult result = loop.execute("Do something", chatClient, tools);

            assertThat(result.status()).isEqualTo(LoopStatus.FAILED);
            assertThat(result.reason()).isEqualTo(TerminationReason.ERROR);
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        @DisplayName("should notify listener on failure")
        void shouldNotifyListenerOnFailure() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("API error"));

            List<String> events = new ArrayList<>();

            TurnLimitedLoop.LoopListener listener = new TurnLimitedLoop.LoopListener() {
                @Override
                public void onLoopFailed(TurnLimitedResult result, Throwable error) {
                    events.add("failed:" + error.getMessage());
                }
            };

            TurnLimitedLoop loop = TurnLimitedLoop.builder()
                    .config(config)
                    .listener(listener)
                    .build();

            loop.execute("Do something", chatClient, tools);

            assertThat(events).hasSize(1);
            assertThat(events.get(0)).contains("failed:");
        }
    }

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("should require config")
        void shouldRequireConfig() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> TurnLimitedLoop.builder().build()
            );
        }
    }
}
