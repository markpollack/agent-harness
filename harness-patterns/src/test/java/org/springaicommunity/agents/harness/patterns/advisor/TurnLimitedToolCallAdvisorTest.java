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
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TurnLimitedToolCallAdvisor")
class TurnLimitedToolCallAdvisorTest {

    @Mock
    private ChatClientRequest request;

    @Mock
    private ChatClientResponse response;

    @Mock
    private CallAdvisorChain chain;

    @Mock
    private ToolCallingManager toolCallingManager;

    @Nested
    @DisplayName("Builder")
    class BuilderTest {

        @Test
        @DisplayName("should create advisor with default maxTurns of 10")
        void shouldCreateWithDefaultMaxTurns() {
            var advisor = TurnLimitedToolCallAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            assertThat(advisor.getMaxTurns()).isEqualTo(10);
        }

        @Test
        @DisplayName("should create advisor with custom maxTurns")
        void shouldCreateWithCustomMaxTurns() {
            var advisor = TurnLimitedToolCallAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .maxTurns(5)
                    .build();

            assertThat(advisor.getMaxTurns()).isEqualTo(5);
        }

        @Test
        @DisplayName("should reject maxTurns less than 1")
        void shouldRejectMaxTurnsLessThanOne() {
            assertThatThrownBy(() -> TurnLimitedToolCallAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .maxTurns(0)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns must be at least 1");
        }

        @Test
        @DisplayName("should reject negative maxTurns")
        void shouldRejectNegativeMaxTurns() {
            assertThatThrownBy(() -> TurnLimitedToolCallAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .maxTurns(-5)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns must be at least 1");
        }
    }

    @Nested
    @DisplayName("Turn counting")
    class TurnCounting {

        private TurnLimitedToolCallAdvisor advisor;

        @BeforeEach
        void setUp() {
            advisor = TurnLimitedToolCallAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .maxTurns(3)
                    .build();
        }

        @Test
        @DisplayName("should reset turn count on doInitializeLoop")
        void shouldResetTurnCountOnInitialize() {
            // Simulate some turns happened before
            advisor.doAfterCall(response, chain);
            assertThat(advisor.getCurrentTurnCount()).isEqualTo(1);

            // Initialize should reset
            advisor.doInitializeLoop(request, chain);
            assertThat(advisor.getCurrentTurnCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should increment turn count on doAfterCall")
        void shouldIncrementTurnCountOnAfterCall() {
            advisor.doInitializeLoop(request, chain);

            advisor.doAfterCall(response, chain);
            assertThat(advisor.getCurrentTurnCount()).isEqualTo(1);

            advisor.doAfterCall(response, chain);
            assertThat(advisor.getCurrentTurnCount()).isEqualTo(2);

            advisor.doAfterCall(response, chain);
            assertThat(advisor.getCurrentTurnCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should throw TurnLimitExceededException when limit exceeded")
        void shouldThrowWhenLimitExceeded() {
            advisor.doInitializeLoop(request, chain);

            // First 3 turns should succeed
            advisor.doAfterCall(response, chain);
            advisor.doAfterCall(response, chain);
            advisor.doAfterCall(response, chain);

            // 4th turn should throw
            assertThatThrownBy(() -> advisor.doAfterCall(response, chain))
                    .isInstanceOf(TurnLimitExceededException.class)
                    .satisfies(ex -> {
                        var e = (TurnLimitExceededException) ex;
                        assertThat(e.getMaxTurns()).isEqualTo(3);
                        assertThat(e.getActualTurns()).isEqualTo(4);
                        assertThat(e.getLastResponse()).isSameAs(response);
                    });
        }

        @Test
        @DisplayName("should allow exactly maxTurns turns")
        void shouldAllowExactlyMaxTurns() {
            advisor.doInitializeLoop(request, chain);

            // All 3 turns should succeed without exception
            for (int i = 0; i < 3; i++) {
                advisor.doAfterCall(response, chain);
            }

            assertThat(advisor.getCurrentTurnCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("TurnLimitExceededException")
    class ExceptionTest {

        @Test
        @DisplayName("should include partial output from response")
        void shouldIncludePartialOutput() {
            var assistantMessage = new AssistantMessage("Partial result before limit");
            var generation = new Generation(assistantMessage);
            var chatResponse = ChatResponse.builder()
                    .generations(List.of(generation))
                    .build();
            var clientResponse = ChatClientResponse.builder()
                    .chatResponse(chatResponse)
                    .build();

            var exception = new TurnLimitExceededException(5, 6, clientResponse);

            assertThat(exception.getPartialOutput()).isEqualTo("Partial result before limit");
            assertThat(exception.getMessage()).contains("6/5");
        }

        @Test
        @DisplayName("should handle null response gracefully")
        void shouldHandleNullResponse() {
            var exception = new TurnLimitExceededException(5, 6, null);

            assertThat(exception.getPartialOutput()).isNull();
            assertThat(exception.getLastResponse()).isNull();
        }

        @Test
        @DisplayName("should handle response with null chatResponse")
        void shouldHandleNullChatResponse() {
            var clientResponse = ChatClientResponse.builder().build();

            var exception = new TurnLimitExceededException(5, 6, clientResponse);

            assertThat(exception.getPartialOutput()).isNull();
        }
    }

    @Nested
    @DisplayName("Advisor metadata")
    class MetadataTest {

        @Test
        @DisplayName("should return descriptive name")
        void shouldReturnDescriptiveName() {
            var advisor = TurnLimitedToolCallAdvisor.builder()
                    .toolCallingManager(toolCallingManager)
                    .build();

            assertThat(advisor.getName()).isEqualTo("Turn Limited Tool Calling Advisor");
        }
    }
}
