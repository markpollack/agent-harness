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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentLoopConfig")
class AgentLoopConfigTest {

    @Nested
    @DisplayName("defaults()")
    class Defaults {

        @Test
        @DisplayName("should have sensible defaults")
        void shouldHaveSensibleDefaults() {
            AgentLoopConfig config = AgentLoopConfig.defaults();

            assertThat(config.maxTurns()).isEqualTo(AgentLoopConfig.DEFAULT_MAX_TURNS);
            assertThat(config.timeout()).isEqualTo(AgentLoopConfig.DEFAULT_TIMEOUT);
            assertThat(config.costLimit()).isEqualTo(AgentLoopConfig.DEFAULT_COST_LIMIT);
            assertThat(config.stuckThreshold()).isEqualTo(AgentLoopConfig.DEFAULT_STUCK_THRESHOLD);
            assertThat(config.juryEvaluationInterval()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("forCli() should have CLI-optimized settings")
        void forCliShouldHaveCliSettings() {
            AgentLoopConfig config = AgentLoopConfig.forCli();

            assertThat(config.maxTurns()).isEqualTo(20);
            assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(config.costLimit()).isEqualTo(5.0);
            assertThat(config.stuckThreshold()).isEqualTo(3);
            assertThat(config.juryEvaluationInterval()).isEqualTo(0);
        }

        @Test
        @DisplayName("forBenchmark() should have benchmark-optimized settings")
        void forBenchmarkShouldHaveBenchmarkSettings() {
            AgentLoopConfig config = AgentLoopConfig.forBenchmark();

            assertThat(config.maxTurns()).isEqualTo(50);
            assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(config.costLimit()).isEqualTo(10.0);
            assertThat(config.stuckThreshold()).isEqualTo(3);
            assertThat(config.juryEvaluationInterval()).isEqualTo(5);
        }

        @Test
        @DisplayName("forAutonomous() should have autonomous-optimized settings")
        void forAutonomousShouldHaveAutonomousSettings() {
            AgentLoopConfig config = AgentLoopConfig.forAutonomous();

            assertThat(config.maxTurns()).isEqualTo(30);
            assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(10));
            assertThat(config.costLimit()).isEqualTo(2.0);
            assertThat(config.stuckThreshold()).isEqualTo(2);
            assertThat(config.juryEvaluationInterval()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("should reject maxTurns less than 1")
        void shouldRejectMaxTurnsLessThanOne() {
            assertThatThrownBy(() -> new AgentLoopConfig(0, Duration.ofMinutes(5), 5.0, 3, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns must be at least 1");

            assertThatThrownBy(() -> new AgentLoopConfig(-1, Duration.ofMinutes(5), 5.0, 3, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns must be at least 1");
        }

        @Test
        @DisplayName("should reject null timeout")
        void shouldRejectNullTimeout() {
            assertThatThrownBy(() -> new AgentLoopConfig(20, null, 5.0, 3, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must not be null");
        }

        @Test
        @DisplayName("should reject negative costLimit")
        void shouldRejectNegativeCostLimit() {
            assertThatThrownBy(() -> new AgentLoopConfig(20, Duration.ofMinutes(5), -1.0, 3, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("costLimit must not be negative");
        }

        @Test
        @DisplayName("should reject negative stuckThreshold")
        void shouldRejectNegativeStuckThreshold() {
            assertThatThrownBy(() -> new AgentLoopConfig(20, Duration.ofMinutes(5), 5.0, -1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stuckThreshold must not be negative");
        }

        @Test
        @DisplayName("should reject negative juryEvaluationInterval")
        void shouldRejectNegativeJuryEvaluationInterval() {
            assertThatThrownBy(() -> new AgentLoopConfig(20, Duration.ofMinutes(5), 5.0, 3, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("juryEvaluationInterval must not be negative");
        }

        @Test
        @DisplayName("should accept zero costLimit (disabled)")
        void shouldAcceptZeroCostLimit() {
            AgentLoopConfig config = new AgentLoopConfig(20, Duration.ofMinutes(5), 0.0, 3, 0);
            assertThat(config.costLimit()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should accept zero stuckThreshold (disabled)")
        void shouldAcceptZeroStuckThreshold() {
            AgentLoopConfig config = new AgentLoopConfig(20, Duration.ofMinutes(5), 5.0, 0, 0);
            assertThat(config.stuckThreshold()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Record equality")
    class RecordEquality {

        @Test
        @DisplayName("configs with same values should be equal")
        void configsWithSameValuesShouldBeEqual() {
            AgentLoopConfig config1 = new AgentLoopConfig(20, Duration.ofMinutes(5), 5.0, 3, 0);
            AgentLoopConfig config2 = new AgentLoopConfig(20, Duration.ofMinutes(5), 5.0, 3, 0);

            assertThat(config1).isEqualTo(config2);
            assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
        }

        @Test
        @DisplayName("configs with different values should not be equal")
        void configsWithDifferentValuesShouldNotBeEqual() {
            AgentLoopConfig config1 = new AgentLoopConfig(20, Duration.ofMinutes(5), 5.0, 3, 0);
            AgentLoopConfig config2 = new AgentLoopConfig(30, Duration.ofMinutes(5), 5.0, 3, 0);

            assertThat(config1).isNotEqualTo(config2);
        }
    }
}
