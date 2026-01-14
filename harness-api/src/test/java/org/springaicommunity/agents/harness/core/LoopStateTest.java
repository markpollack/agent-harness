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
package org.springaicommunity.agents.harness.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoopState")
class LoopStateTest {

    @Nested
    @DisplayName("initial()")
    class Initial {

        @Test
        @DisplayName("should create initial state with correct defaults")
        void shouldCreateInitialStateWithCorrectDefaults() {
            LoopState state = LoopState.initial("test-run-123");

            assertThat(state.runId()).isEqualTo("test-run-123");
            assertThat(state.currentTurn()).isEqualTo(0);
            assertThat(state.totalTokensUsed()).isEqualTo(0L);
            assertThat(state.estimatedCost()).isEqualTo(0.0);
            assertThat(state.abortSignalled()).isFalse();
            assertThat(state.turnHistory()).isEmpty();
            assertThat(state.consecutiveSameOutputCount()).isEqualTo(0);
            assertThat(state.startedAt()).isNotNull();
        }

        @Test
        @DisplayName("should set startedAt to current time")
        void shouldSetStartedAtToCurrentTime() {
            Instant before = Instant.now();
            LoopState state = LoopState.initial("test-run");
            Instant after = Instant.now();

            assertThat(state.startedAt())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        }
    }

    @Nested
    @DisplayName("completeTurn()")
    class CompleteTurn {

        @Test
        @DisplayName("should increment turn counter")
        void shouldIncrementTurnCounter() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 12345);

            assertThat(state.currentTurn()).isEqualTo(1);
        }

        @Test
        @DisplayName("should accumulate tokens")
        void shouldAccumulateTokens() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 1)
                    .completeTurn(200, 0.02, true, 2)
                    .completeTurn(150, 0.015, true, 3);

            assertThat(state.totalTokensUsed()).isEqualTo(450);
        }

        @Test
        @DisplayName("should accumulate cost")
        void shouldAccumulateCost() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 1)
                    .completeTurn(200, 0.02, true, 2);

            assertThat(state.estimatedCost()).isEqualTo(0.03);
        }

        @Test
        @DisplayName("should add turn snapshot to history")
        void shouldAddTurnSnapshotToHistory() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 12345)
                    .completeTurn(200, 0.02, false, 67890);

            assertThat(state.turnHistory()).hasSize(2);

            LoopState.TurnSnapshot first = state.turnHistory().get(0);
            assertThat(first.turn()).isEqualTo(0);
            assertThat(first.tokensUsed()).isEqualTo(100);
            assertThat(first.cost()).isEqualTo(0.01);
            assertThat(first.hadToolCalls()).isTrue();
            assertThat(first.outputSignature()).isEqualTo(12345);

            LoopState.TurnSnapshot second = state.turnHistory().get(1);
            assertThat(second.turn()).isEqualTo(1);
            assertThat(second.tokensUsed()).isEqualTo(200);
            assertThat(second.hadToolCalls()).isFalse();
        }

        @Test
        @DisplayName("should preserve immutability")
        void shouldPreserveImmutability() {
            LoopState state1 = LoopState.initial("test-run");
            LoopState state2 = state1.completeTurn(100, 0.01, true, 12345);

            assertThat(state1.currentTurn()).isEqualTo(0);
            assertThat(state1.totalTokensUsed()).isEqualTo(0);
            assertThat(state2.currentTurn()).isEqualTo(1);
            assertThat(state2.totalTokensUsed()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Stuck detection")
    class StuckDetection {

        @Test
        @DisplayName("should detect stuck when same output repeated")
        void shouldDetectStuckWhenSameOutputRepeated() {
            int sameSignature = 99999;

            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, sameSignature)
                    .completeTurn(100, 0.01, true, sameSignature)
                    .completeTurn(100, 0.01, true, sameSignature);

            assertThat(state.consecutiveSameOutputCount()).isEqualTo(3);
            assertThat(state.isStuck(3)).isTrue();
            assertThat(state.isStuck(4)).isFalse();
        }

        @Test
        @DisplayName("should reset count when output changes")
        void shouldResetCountWhenOutputChanges() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 111)
                    .completeTurn(100, 0.01, true, 111)
                    .completeTurn(100, 0.01, true, 222) // Different output
                    .completeTurn(100, 0.01, true, 333);

            assertThat(state.consecutiveSameOutputCount()).isEqualTo(1);
            assertThat(state.isStuck(2)).isFalse();
        }

        @Test
        @DisplayName("should count correctly with interleaved same outputs")
        void shouldCountCorrectlyWithInterleavedSameOutputs() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 111)
                    .completeTurn(100, 0.01, true, 222)
                    .completeTurn(100, 0.01, true, 111) // Same as first, but not consecutive
                    .completeTurn(100, 0.01, true, 111);

            assertThat(state.consecutiveSameOutputCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("abort()")
    class Abort {

        @Test
        @DisplayName("should set abortSignalled to true")
        void shouldSetAbortSignalledToTrue() {
            LoopState state = LoopState.initial("test-run").abort();

            assertThat(state.abortSignalled()).isTrue();
        }

        @Test
        @DisplayName("should preserve other state")
        void shouldPreserveOtherState() {
            LoopState original = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 12345);

            LoopState aborted = original.abort();

            assertThat(aborted.runId()).isEqualTo("test-run");
            assertThat(aborted.currentTurn()).isEqualTo(1);
            assertThat(aborted.totalTokensUsed()).isEqualTo(100);
            assertThat(aborted.abortSignalled()).isTrue();
        }

        @Test
        @DisplayName("should be immutable")
        void shouldBeImmutable() {
            LoopState original = LoopState.initial("test-run");
            LoopState aborted = original.abort();

            assertThat(original.abortSignalled()).isFalse();
            assertThat(aborted.abortSignalled()).isTrue();
        }
    }

    @Nested
    @DisplayName("elapsed()")
    class Elapsed {

        @Test
        @DisplayName("should return positive duration")
        void shouldReturnPositiveDuration() throws InterruptedException {
            LoopState state = LoopState.initial("test-run");

            // Small delay to ensure elapsed time is measurable
            Thread.sleep(10);

            Duration elapsed = state.elapsed();

            assertThat(elapsed).isPositive();
            assertThat(elapsed.toMillis()).isGreaterThanOrEqualTo(10);
        }
    }

    @Nested
    @DisplayName("lastTurn()")
    class LastTurn {

        @Test
        @DisplayName("should return empty when no turns completed")
        void shouldReturnEmptyWhenNoTurnsCompleted() {
            LoopState state = LoopState.initial("test-run");

            assertThat(state.lastTurn()).isEmpty();
        }

        @Test
        @DisplayName("should return last turn snapshot")
        void shouldReturnLastTurnSnapshot() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 111)
                    .completeTurn(200, 0.02, false, 222);

            assertThat(state.lastTurn()).isPresent();
            LoopState.TurnSnapshot last = state.lastTurn().get();
            assertThat(last.turn()).isEqualTo(1);
            assertThat(last.tokensUsed()).isEqualTo(200);
            assertThat(last.outputSignature()).isEqualTo(222);
        }
    }

    @Nested
    @DisplayName("Termination condition checks")
    class TerminationConditions {

        @Test
        @DisplayName("costExceeded() should return true when cost exceeds limit")
        void costExceededShouldReturnTrueWhenCostExceedsLimit() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(1000, 5.0, true, 1)
                    .completeTurn(1000, 5.0, true, 2);

            assertThat(state.costExceeded(9.0)).isTrue();
            assertThat(state.costExceeded(10.0)).isFalse();
            assertThat(state.costExceeded(11.0)).isFalse();
        }

        @Test
        @DisplayName("costExceeded() should return false for zero or negative limit")
        void costExceededShouldReturnFalseForZeroOrNegativeLimit() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(1000, 5.0, true, 1);

            assertThat(state.costExceeded(0)).isFalse();
            assertThat(state.costExceeded(-1)).isFalse();
        }

        @Test
        @DisplayName("timeoutExceeded() should return true when elapsed > timeout")
        void timeoutExceededShouldReturnTrueWhenElapsedExceedsTimeout() throws InterruptedException {
            LoopState state = LoopState.initial("test-run");

            Thread.sleep(50);

            assertThat(state.timeoutExceeded(Duration.ofMillis(10))).isTrue();
            assertThat(state.timeoutExceeded(Duration.ofSeconds(10))).isFalse();
        }

        @Test
        @DisplayName("maxTurnsReached() should return true when turns >= max")
        void maxTurnsReachedShouldReturnTrueWhenTurnsReachMax() {
            LoopState state = LoopState.initial("test-run")
                    .completeTurn(100, 0.01, true, 1)
                    .completeTurn(100, 0.01, true, 2)
                    .completeTurn(100, 0.01, true, 3);

            assertThat(state.maxTurnsReached(3)).isTrue();
            assertThat(state.maxTurnsReached(4)).isFalse();
            assertThat(state.maxTurnsReached(2)).isTrue();
        }
    }

    @Nested
    @DisplayName("Record equality")
    class RecordEquality {

        @Test
        @DisplayName("states with same values should be equal")
        void statesWithSameValuesShouldBeEqual() {
            Instant now = Instant.now();

            LoopState state1 = new LoopState(
                    "run-1", 5, now, 1000, 0.05,
                    false, java.util.List.of(), 0
            );

            LoopState state2 = new LoopState(
                    "run-1", 5, now, 1000, 0.05,
                    false, java.util.List.of(), 0
            );

            assertThat(state1).isEqualTo(state2);
            assertThat(state1.hashCode()).isEqualTo(state2.hashCode());
        }
    }
}
