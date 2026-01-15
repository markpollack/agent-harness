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
import org.springframework.ai.model.tool.ToolCallingManager;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
