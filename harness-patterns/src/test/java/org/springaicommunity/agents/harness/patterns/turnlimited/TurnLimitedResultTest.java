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

import org.springaicommunity.agents.harness.core.LoopState;
import org.springaicommunity.agents.harness.core.LoopStatus;
import org.springaicommunity.agents.harness.core.TerminationReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TurnLimitedResult")
class TurnLimitedResultTest {

    private LoopState state;

    @BeforeEach
    void setUp() {
        // Create a state with some turns completed
        state = LoopState.initial("test-run-id")
                .completeTurn(1000, 0.01, true, 12345)
                .completeTurn(800, 0.008, true, 12346);
    }

    @Nested
    @DisplayName("Factory method: success()")
    class SuccessFactory {

        @Test
        @DisplayName("should create successful result")
        void shouldCreateSuccessfulResult() {
            TurnLimitedResult result = TurnLimitedResult.success(
                    "test-run-id",
                    "Task completed successfully",
                    state,
                    null
            );

            assertThat(result.runId()).isEqualTo("test-run-id");
            assertThat(result.output()).isEqualTo("Task completed successfully");
            assertThat(result.status()).isEqualTo(LoopStatus.COMPLETED);
            assertThat(result.reason()).isEqualTo(TerminationReason.FINISH_TOOL_CALLED);
            assertThat(result.turnsCompleted()).isEqualTo(2);
            assertThat(result.totalTokens()).isEqualTo(1800);
            assertThat(result.estimatedCost()).isCloseTo(0.018, org.assertj.core.data.Offset.offset(0.0001));
            assertThat(result.finalState()).isEqualTo(state);
            assertThat(result.lastVerdict()).isNull();
        }

        @Test
        @DisplayName("should indicate success via isSuccess()")
        void shouldIndicateSuccessViaIsSuccess() {
            TurnLimitedResult result = TurnLimitedResult.success(
                    "test-run-id", "done", state, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isFailure()).isFalse();
        }
    }

    @Nested
    @DisplayName("Factory method: terminated()")
    class TerminatedFactory {

        @Test
        @DisplayName("should create terminated result with MAX_TURNS_REACHED")
        void shouldCreateTerminatedResultWithMaxTurns() {
            TurnLimitedResult result = TurnLimitedResult.terminated(
                    "test-run-id",
                    "Reached max turns",
                    TerminationReason.MAX_TURNS_REACHED,
                    state,
                    null
            );

            assertThat(result.status()).isEqualTo(LoopStatus.COMPLETED);
            assertThat(result.reason()).isEqualTo(TerminationReason.MAX_TURNS_REACHED);
            assertThat(result.maxTurnsReached()).isTrue();
            assertThat(result.wasStuck()).isFalse();
            assertThat(result.timedOut()).isFalse();
        }

        @Test
        @DisplayName("should create terminated result with TIMEOUT")
        void shouldCreateTerminatedResultWithTimeout() {
            TurnLimitedResult result = TurnLimitedResult.terminated(
                    "test-run-id",
                    "Timed out",
                    TerminationReason.TIMEOUT,
                    state,
                    null
            );

            assertThat(result.reason()).isEqualTo(TerminationReason.TIMEOUT);
            assertThat(result.timedOut()).isTrue();
            assertThat(result.maxTurnsReached()).isFalse();
        }

        @Test
        @DisplayName("should create terminated result with STUCK_DETECTED")
        void shouldCreateTerminatedResultWithStuck() {
            TurnLimitedResult result = TurnLimitedResult.terminated(
                    "test-run-id",
                    "Agent stuck",
                    TerminationReason.STUCK_DETECTED,
                    state,
                    null
            );

            assertThat(result.reason()).isEqualTo(TerminationReason.STUCK_DETECTED);
            assertThat(result.wasStuck()).isTrue();
        }

        @Test
        @DisplayName("should create terminated result with FINISH_TOOL_CALLED")
        void shouldCreateTerminatedResultWithFinishTool() {
            TurnLimitedResult result = TurnLimitedResult.terminated(
                    "test-run-id",
                    "Task done",
                    TerminationReason.FINISH_TOOL_CALLED,
                    state,
                    null
            );

            assertThat(result.reason()).isEqualTo(TerminationReason.FINISH_TOOL_CALLED);
            assertThat(result.finishToolCalled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Factory method: failed()")
    class FailedFactory {

        @Test
        @DisplayName("should create failed result")
        void shouldCreateFailedResult() {
            TurnLimitedResult result = TurnLimitedResult.failed("test-run-id", state);

            assertThat(result.runId()).isEqualTo("test-run-id");
            assertThat(result.output()).isNull();
            assertThat(result.status()).isEqualTo(LoopStatus.FAILED);
            assertThat(result.reason()).isEqualTo(TerminationReason.ERROR);
            assertThat(result.turnsCompleted()).isEqualTo(2);
            assertThat(result.lastVerdict()).isNull();
        }

        @Test
        @DisplayName("should indicate failure via isFailure()")
        void shouldIndicateFailureViaIsFailure() {
            TurnLimitedResult result = TurnLimitedResult.failed("test-run-id", state);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("Query methods")
    class QueryMethods {

        @Test
        @DisplayName("finalScore() should return 0.0 when no verdict")
        void finalScoreShouldReturnZeroWhenNoVerdict() {
            TurnLimitedResult result = TurnLimitedResult.success(
                    "test-run-id", "done", state, null);

            assertThat(result.finalScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("juryPassed() should return false when no verdict")
        void juryPassedShouldReturnFalseWhenNoVerdict() {
            TurnLimitedResult result = TurnLimitedResult.success(
                    "test-run-id", "done", state, null);

            assertThat(result.juryPassed()).isFalse();
        }
    }

    @Nested
    @DisplayName("LoopResult interface")
    class LoopResultInterface {

        @Test
        @DisplayName("should implement LoopResult methods")
        void shouldImplementLoopResultMethods() {
            TurnLimitedResult result = TurnLimitedResult.success(
                    "test-run-id", "done", state, null);

            assertThat(result.runId()).isEqualTo("test-run-id");
            assertThat(result.output()).isEqualTo("done");
            assertThat(result.status()).isEqualTo(LoopStatus.COMPLETED);
            assertThat(result.turnsCompleted()).isEqualTo(2);
            assertThat(result.totalTokens()).isEqualTo(1800);
            assertThat(result.estimatedCost()).isGreaterThan(0);
            assertThat(result.totalDuration()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Record equals/hashCode")
    class RecordEquality {

        @Test
        @DisplayName("results with same values should be equal")
        void resultsWithSameValuesShouldBeEqual() {
            LoopState stateA = LoopState.initial("run-1");
            LoopState stateB = LoopState.initial("run-1");

            // Note: elapsed time will differ, so we need to create at same instant
            // For testing equality, we'll just verify the record equality contract
            TurnLimitedResult result1 = new TurnLimitedResult(
                    "run-1", "output", LoopStatus.COMPLETED,
                    TerminationReason.FINISH_TOOL_CALLED,
                    5, Duration.ofMinutes(1), 1000, 0.01,
                    stateA, null
            );

            TurnLimitedResult result2 = new TurnLimitedResult(
                    "run-1", "output", LoopStatus.COMPLETED,
                    TerminationReason.FINISH_TOOL_CALLED,
                    5, Duration.ofMinutes(1), 1000, 0.01,
                    stateA, null
            );

            assertThat(result1).isEqualTo(result2);
            assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
        }
    }
}
