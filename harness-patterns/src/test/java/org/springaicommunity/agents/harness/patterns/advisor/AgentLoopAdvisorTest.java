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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agents.harness.core.LoopState;
import org.springaicommunity.agents.harness.core.TerminationReason;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopAdvisor")
class AgentLoopAdvisorTest {

    @Mock
    private ToolCallingManager toolCallingManager;

    @Nested
    @DisplayName("Builder")
    class Builder {

        @Test
        @DisplayName("should build with defaults")
        void shouldBuildWithDefaults() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            AgentLoopConfig config = advisor.getConfig();
            assertThat(config.maxTurns()).isEqualTo(AgentLoopConfig.DEFAULT_MAX_TURNS);
            assertThat(config.timeout()).isEqualTo(AgentLoopConfig.DEFAULT_TIMEOUT);
            assertThat(config.costLimit()).isEqualTo(AgentLoopConfig.DEFAULT_COST_LIMIT);
            assertThat(config.stuckThreshold()).isEqualTo(AgentLoopConfig.DEFAULT_STUCK_THRESHOLD);
        }

        @Test
        @DisplayName("should accept custom values")
        void shouldAcceptCustomValues() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .maxTurns(50)
                    .timeout(Duration.ofMinutes(30))
                    .costLimit(10.0)
                    .stuckThreshold(5)
                    .build();

            AgentLoopConfig config = advisor.getConfig();
            assertThat(config.maxTurns()).isEqualTo(50);
            assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(config.costLimit()).isEqualTo(10.0);
            assertThat(config.stuckThreshold()).isEqualTo(5);
        }

        @Test
        @DisplayName("should accept AgentLoopConfig preset")
        void shouldAcceptConfigPreset() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .config(AgentLoopConfig.forBenchmark())
                    .build();

            AgentLoopConfig config = advisor.getConfig();
            assertThat(config.maxTurns()).isEqualTo(50);
            assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(config.juryEvaluationInterval()).isEqualTo(5);
        }

        @Test
        @DisplayName("should have correct name")
        void shouldHaveCorrectName() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            assertThat(advisor.getName()).isEqualTo("Agent Loop Advisor");
        }
    }

    @Nested
    @DisplayName("Abort signal")
    class AbortSignal {

        @Test
        @DisplayName("should start with abort signal false")
        void shouldStartWithAbortSignalFalse() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            assertThat(advisor.isAbortSignalled()).isFalse();
        }

        @Test
        @DisplayName("should set abort signal to true")
        void shouldSetAbortSignalToTrue() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            advisor.abort();

            assertThat(advisor.isAbortSignalled()).isTrue();
        }
    }

    @Nested
    @DisplayName("AgentLoopListener")
    class ListenerTests {

        private AtomicInteger loopStartedCount;
        private AtomicInteger turnStartedCount;
        private AtomicInteger turnCompletedCount;
        private AtomicReference<TerminationReason> lastReason;

        @BeforeEach
        void setUp() {
            loopStartedCount = new AtomicInteger(0);
            turnStartedCount = new AtomicInteger(0);
            turnCompletedCount = new AtomicInteger(0);
            lastReason = new AtomicReference<>();
        }

        @Test
        @DisplayName("should add listener via builder")
        void shouldAddListenerViaBuilder() {
            AgentLoopListener listener = new AgentLoopListener() {
                @Override
                public void onLoopStarted(String runId, String userMessage) {
                    loopStartedCount.incrementAndGet();
                }

                @Override
                public void onTurnStarted(String runId, int turn) {
                    turnStartedCount.incrementAndGet();
                }
            };

            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .listener(listener)
                    .build();

            // Advisor created with listener - can't easily test notifications
            // without mocking the full ChatClient chain
            assertThat(advisor).isNotNull();
        }

        @Test
        @DisplayName("should add multiple listeners")
        void shouldAddMultipleListeners() {
            AgentLoopListener listener1 = new AgentLoopListener() {};
            AgentLoopListener listener2 = new AgentLoopListener() {};

            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .listener(listener1)
                    .listener(listener2)
                    .build();

            assertThat(advisor).isNotNull();
        }
    }

    @Nested
    @DisplayName("AgentLoopTerminatedException")
    class TerminatedExceptionTests {

        @Test
        @DisplayName("should carry reason and state")
        void shouldCarryReasonAndState() {
            LoopState state = LoopState.initial("test-run");
            AgentLoopTerminatedException ex = new AgentLoopTerminatedException(
                    TerminationReason.MAX_TURNS_REACHED,
                    "Max turns reached: 20",
                    state
            );

            assertThat(ex.getReason()).isEqualTo(TerminationReason.MAX_TURNS_REACHED);
            assertThat(ex.getState()).isEqualTo(state);
            assertThat(ex.getMessage()).isEqualTo("Max turns reached: 20");
        }

        @Test
        @DisplayName("isSuccessfulTermination should identify success cases")
        void isSuccessfulTerminationShouldIdentifySuccessCases() {
            LoopState state = LoopState.initial("test-run");

            AgentLoopTerminatedException successEx = new AgentLoopTerminatedException(
                    TerminationReason.SCORE_THRESHOLD_MET, "Passed", state
            );
            assertThat(successEx.isSuccessfulTermination()).isTrue();

            AgentLoopTerminatedException finishEx = new AgentLoopTerminatedException(
                    TerminationReason.FINISH_TOOL_CALLED, "Done", state
            );
            assertThat(finishEx.isSuccessfulTermination()).isTrue();

            AgentLoopTerminatedException failEx = new AgentLoopTerminatedException(
                    TerminationReason.MAX_TURNS_REACHED, "Limit", state
            );
            assertThat(failEx.isSuccessfulTermination()).isFalse();
        }
    }

    @Nested
    @DisplayName("Configuration validation in Builder")
    class BuilderValidation {

        @Test
        @DisplayName("should reject invalid maxTurns")
        void shouldRejectInvalidMaxTurns() {
            assertThatThrownBy(() ->
                    AgentLoopAdvisor.builder()
                            .toolCallingManager(toolCallingManager)
                            .maxTurns(0)
                            .build()
            ).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject null timeout")
        void shouldRejectNullTimeout() {
            assertThatThrownBy(() ->
                    AgentLoopAdvisor.builder()
                            .toolCallingManager(toolCallingManager)
                            .timeout(null)
                            .build()
            ).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject negative costLimit")
        void shouldRejectNegativeCostLimit() {
            assertThatThrownBy(() ->
                    AgentLoopAdvisor.builder()
                            .toolCallingManager(toolCallingManager)
                            .costLimit(-1.0)
                            .build()
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Loop initialization")
    class LoopInitialization {

        @Test
        @DisplayName("doInitializeLoopShouldCreateFreshState")
        void doInitializeLoopShouldCreateFreshState() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            ChatClientRequest request = createMockRequest("Test message");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);

            advisor.doInitializeLoop(request, chain);

            LoopState state = advisor.getCurrentState();
            assertThat(state).isNotNull();
            assertThat(state.runId()).isNotNull();
            assertThat(state.currentTurn()).isEqualTo(0);
            assertThat(state.totalTokensUsed()).isEqualTo(0);
        }

        @Test
        @DisplayName("doInitializeLoopShouldResetAbortSignal")
        void doInitializeLoopShouldResetAbortSignal() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            advisor.abort();
            assertThat(advisor.isAbortSignalled()).isTrue();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);

            advisor.doInitializeLoop(request, chain);

            assertThat(advisor.isAbortSignalled()).isFalse();
        }

        @Test
        @DisplayName("doInitializeLoopShouldNotifyListeners")
        void doInitializeLoopShouldNotifyListeners() {
            AtomicReference<String> capturedRunId = new AtomicReference<>();
            AtomicReference<String> capturedMessage = new AtomicReference<>();

            AgentLoopListener listener = new AgentLoopListener() {
                @Override
                public void onLoopStarted(String runId, String userMessage) {
                    capturedRunId.set(runId);
                    capturedMessage.set(userMessage);
                }
            };

            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .listener(listener)
                    .build();

            ChatClientRequest request = createMockRequest("Hello agent");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);

            advisor.doInitializeLoop(request, chain);

            assertThat(capturedRunId.get()).isNotNull();
            assertThat(capturedMessage.get()).isEqualTo("Hello agent");
        }
    }

    @Nested
    @DisplayName("Before call checks")
    class BeforeCallChecks {

        @Test
        @DisplayName("doBeforeCallShouldThrowWhenAbortSignalled")
        void doBeforeCallShouldThrowWhenAbortSignalled() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            // Initialize first
            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);

            // Signal abort
            advisor.abort();

            // Should throw on next call
            assertThatThrownBy(() -> advisor.doBeforeCall(request, chain))
                    .isInstanceOf(AgentLoopTerminatedException.class)
                    .satisfies(ex -> {
                        AgentLoopTerminatedException terminated = (AgentLoopTerminatedException) ex;
                        assertThat(terminated.getReason()).isEqualTo(TerminationReason.EXTERNAL_SIGNAL);
                    });
        }

        @Test
        @DisplayName("doBeforeCallShouldThrowWhenMaxTurnsReached")
        void doBeforeCallShouldThrowWhenMaxTurnsReached() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .maxTurns(1)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);

            // First call succeeds, advances turn
            advisor.doBeforeCall(request, chain);
            ChatClientResponse response = createMockResponse(100);
            advisor.doAfterCall(response, chain);

            // Second call should fail (max 1 turn)
            assertThatThrownBy(() -> advisor.doBeforeCall(request, chain))
                    .isInstanceOf(AgentLoopTerminatedException.class)
                    .satisfies(ex -> {
                        AgentLoopTerminatedException terminated = (AgentLoopTerminatedException) ex;
                        assertThat(terminated.getReason()).isEqualTo(TerminationReason.MAX_TURNS_REACHED);
                    });
        }

        @Test
        @DisplayName("doBeforeCallShouldNotifyTurnStarted")
        void doBeforeCallShouldNotifyTurnStarted() {
            AtomicInteger turnStartedCalls = new AtomicInteger(0);
            AtomicInteger lastTurn = new AtomicInteger(-1);

            AgentLoopListener listener = new AgentLoopListener() {
                @Override
                public void onTurnStarted(String runId, int turn) {
                    turnStartedCalls.incrementAndGet();
                    lastTurn.set(turn);
                }
            };

            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .listener(listener)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);
            advisor.doBeforeCall(request, chain);

            assertThat(turnStartedCalls.get()).isEqualTo(1);
            assertThat(lastTurn.get()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("After call processing")
    class AfterCallProcessing {

        @Test
        @DisplayName("doAfterCallShouldUpdateTokenCount")
        void doAfterCallShouldUpdateTokenCount() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);
            advisor.doBeforeCall(request, chain);

            ChatClientResponse response = createMockResponse(500);
            advisor.doAfterCall(response, chain);

            LoopState state = advisor.getCurrentState();
            assertThat(state.totalTokensUsed()).isEqualTo(500);
        }

        @Test
        @DisplayName("doAfterCallShouldIncrementTurnCount")
        void doAfterCallShouldIncrementTurnCount() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);

            assertThat(advisor.getCurrentState().currentTurn()).isEqualTo(0);

            advisor.doBeforeCall(request, chain);
            advisor.doAfterCall(createMockResponse(100), chain);

            assertThat(advisor.getCurrentState().currentTurn()).isEqualTo(1);
        }

        @Test
        @DisplayName("doAfterCallShouldNotifyTurnCompleted")
        void doAfterCallShouldNotifyTurnCompleted() {
            AtomicInteger turnCompletedCalls = new AtomicInteger(0);

            AgentLoopListener listener = new AgentLoopListener() {
                @Override
                public void onTurnCompleted(String runId, int turn, TerminationReason reason) {
                    turnCompletedCalls.incrementAndGet();
                }
            };

            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .listener(listener)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);
            advisor.doBeforeCall(request, chain);
            advisor.doAfterCall(createMockResponse(100), chain);

            assertThat(turnCompletedCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("doAfterCallShouldHandleNullResponse")
        void doAfterCallShouldHandleNullResponse() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);
            advisor.doBeforeCall(request, chain);

            // Should not throw with null response parts
            ChatClientResponse response = mock(ChatClientResponse.class);
            when(response.chatResponse()).thenReturn(null);

            advisor.doAfterCall(response, chain);

            // Token count should be 0
            assertThat(advisor.getCurrentState().totalTokensUsed()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Cost tracking")
    class CostTracking {

        @Test
        @DisplayName("shouldThrowWhenCostLimitExceeded")
        void shouldThrowWhenCostLimitExceeded() {
            // Set very low cost limit
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .costLimit(0.0001) // $0.0001 limit
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);
            advisor.doBeforeCall(request, chain);

            // Add tokens that exceed cost limit (1M tokens at $6/1M = $6)
            ChatClientResponse response = createMockResponse(100000);
            advisor.doAfterCall(response, chain);

            // Next call should fail due to cost
            assertThatThrownBy(() -> advisor.doBeforeCall(request, chain))
                    .isInstanceOf(AgentLoopTerminatedException.class)
                    .satisfies(ex -> {
                        AgentLoopTerminatedException terminated = (AgentLoopTerminatedException) ex;
                        assertThat(terminated.getReason()).isEqualTo(TerminationReason.COST_LIMIT_EXCEEDED);
                    });
        }

        @Test
        @DisplayName("shouldNotCheckCostWhenLimitIsZero")
        void shouldNotCheckCostWhenLimitIsZero() {
            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .costLimit(0) // Disabled
                    .maxTurns(100)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);
            advisor.doBeforeCall(request, chain);

            // Add lots of tokens
            ChatClientResponse response = createMockResponse(1000000);
            advisor.doAfterCall(response, chain);

            // Should not throw - cost checking is disabled
            advisor.doBeforeCall(request, chain);
        }
    }

    @Nested
    @DisplayName("Listener error handling")
    class ListenerErrorHandling {

        @Test
        @DisplayName("shouldContinueWhenListenerThrows")
        void shouldContinueWhenListenerThrows() {
            AgentLoopListener failingListener = new AgentLoopListener() {
                @Override
                public void onLoopStarted(String runId, String userMessage) {
                    throw new RuntimeException("Listener error");
                }
            };

            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .listener(failingListener)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);

            // Should not throw despite listener failure
            advisor.doInitializeLoop(request, chain);

            assertThat(advisor.getCurrentState()).isNotNull();
        }

        @Test
        @DisplayName("shouldNotifyAllListenersEvenWhenOneFails")
        void shouldNotifyAllListenersEvenWhenOneFails() {
            AtomicInteger successCount = new AtomicInteger(0);

            AgentLoopListener failingListener = new AgentLoopListener() {
                @Override
                public void onLoopStarted(String runId, String userMessage) {
                    throw new RuntimeException("First listener fails");
                }
            };

            AgentLoopListener successfulListener = new AgentLoopListener() {
                @Override
                public void onLoopStarted(String runId, String userMessage) {
                    successCount.incrementAndGet();
                }
            };

            AgentLoopAdvisor advisor = AgentLoopAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .listener(failingListener)
                    .listener(successfulListener)
                    .build();

            ChatClientRequest request = createMockRequest("Test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            advisor.doInitializeLoop(request, chain);

            assertThat(successCount.get()).isEqualTo(1);
        }
    }

    // --- Helper methods ---

    private ChatClientRequest createMockRequest(String userMessageText) {
        ChatClientRequest request = mock(ChatClientRequest.class);
        Prompt prompt = mock(Prompt.class);
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessageText));
        when(prompt.getInstructions()).thenReturn(messages);
        when(request.prompt()).thenReturn(prompt);
        return request;
    }

    private ChatClientResponse createMockResponse(int totalTokens) {
        ChatClientResponse response = mock(ChatClientResponse.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        Generation generation = mock(Generation.class);

        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(chatResponse.getResult()).thenReturn(generation);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getTotalTokens()).thenReturn(Integer.valueOf(totalTokens));

        return response;
    }
}
