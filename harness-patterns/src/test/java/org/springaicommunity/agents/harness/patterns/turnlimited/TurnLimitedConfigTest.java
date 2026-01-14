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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TurnLimitedConfig")
class TurnLimitedConfigTest {

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("should have sensible defaults")
        void shouldHaveSensibleDefaults() {
            TurnLimitedConfig config = TurnLimitedConfig.builder().build();

            assertThat(config.maxTurns()).isEqualTo(50);
            assertThat(config.timeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(config.scoreThreshold()).isEqualTo(0.0);
            assertThat(config.stuckThreshold()).isEqualTo(3);
            assertThat(config.costLimit()).isEqualTo(Double.MAX_VALUE);
            assertThat(config.workingDirectory()).isEqualTo(Path.of("."));
            assertThat(config.jury()).isEmpty();
            assertThat(config.evaluateEveryNTurns()).isEqualTo(0);
            assertThat(config.tools()).isEmpty();
            assertThat(config.finishToolName()).isEqualTo("complete_task");
        }
    }

    @Nested
    @DisplayName("Builder methods")
    class BuilderMethods {

        @Test
        @DisplayName("should set all fields correctly")
        void shouldSetAllFieldsCorrectly() {
            TurnLimitedConfig config = TurnLimitedConfig.builder()
                    .maxTurns(100)
                    .timeout(Duration.ofHours(1))
                    .scoreThreshold(0.8)
                    .stuckThreshold(5)
                    .costLimit(50.0)
                    .workingDirectory(Path.of("/tmp"))
                    .evaluateEveryNTurns(3)
                    .tools(List.of("read", "write"))
                    .finishToolName("done")
                    .build();

            assertThat(config.maxTurns()).isEqualTo(100);
            assertThat(config.timeout()).isEqualTo(Duration.ofHours(1));
            assertThat(config.scoreThreshold()).isEqualTo(0.8);
            assertThat(config.stuckThreshold()).isEqualTo(5);
            assertThat(config.costLimit()).isEqualTo(50.0);
            assertThat(config.workingDirectory()).isEqualTo(Path.of("/tmp"));
            assertThat(config.evaluateEveryNTurns()).isEqualTo(3);
            assertThat(config.tools()).containsExactly("read", "write");
            assertThat(config.finishToolName()).isEqualTo("done");
        }

        @Test
        @DisplayName("tool() should add single tool")
        void toolShouldAddSingleTool() {
            TurnLimitedConfig config = TurnLimitedConfig.builder()
                    .tool("read")
                    .tool("write")
                    .tool("execute")
                    .build();

            assertThat(config.tools()).containsExactly("read", "write", "execute");
        }

        @Test
        @DisplayName("tools(Consumer) should modify tools list")
        void toolsConsumerShouldModifyList() {
            TurnLimitedConfig config = TurnLimitedConfig.builder()
                    .tool("read")
                    .tool("write")
                    .tool("dangerous_delete")
                    .tools(tools -> tools.removeIf(t -> t.startsWith("dangerous_")))
                    .build();

            assertThat(config.tools()).containsExactly("read", "write");
        }
    }

    @Nested
    @DisplayName("toBuilder()")
    class ToBuilder {

        @Test
        @DisplayName("should create builder with same values")
        void shouldCreateBuilderWithSameValues() {
            TurnLimitedConfig original = TurnLimitedConfig.builder()
                    .maxTurns(100)
                    .timeout(Duration.ofHours(2))
                    .scoreThreshold(0.9)
                    .tools(List.of("read", "write"))
                    .build();

            TurnLimitedConfig copy = original.toBuilder().build();

            assertThat(copy).isEqualTo(original);
            assertThat(copy.maxTurns()).isEqualTo(100);
            assertThat(copy.timeout()).isEqualTo(Duration.ofHours(2));
            assertThat(copy.scoreThreshold()).isEqualTo(0.9);
            assertThat(copy.tools()).containsExactly("read", "write");
        }

        @Test
        @DisplayName("should allow modifying copied values")
        void shouldAllowModifyingCopiedValues() {
            TurnLimitedConfig original = TurnLimitedConfig.builder()
                    .maxTurns(50)
                    .scoreThreshold(0.8)
                    .build();

            TurnLimitedConfig modified = original.toBuilder()
                    .maxTurns(200)
                    .scoreThreshold(0.95)
                    .build();

            assertThat(original.maxTurns()).isEqualTo(50);
            assertThat(original.scoreThreshold()).isEqualTo(0.8);
            assertThat(modified.maxTurns()).isEqualTo(200);
            assertThat(modified.scoreThreshold()).isEqualTo(0.95);
        }
    }

    @Nested
    @DisplayName("apply()")
    class Apply {

        @Test
        @DisplayName("should apply configuration consumer")
        void shouldApplyConfigurationConsumer() {
            Consumer<TurnLimitedConfig.Builder> strictConfig = builder -> builder
                    .scoreThreshold(0.95)
                    .stuckThreshold(5)
                    .maxTurns(200);

            TurnLimitedConfig config = TurnLimitedConfig.builder()
                    .apply(strictConfig)
                    .timeout(Duration.ofHours(1))
                    .build();

            assertThat(config.scoreThreshold()).isEqualTo(0.95);
            assertThat(config.stuckThreshold()).isEqualTo(5);
            assertThat(config.maxTurns()).isEqualTo(200);
            assertThat(config.timeout()).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("should allow reusing configuration snippets")
        void shouldAllowReusingConfigurationSnippets() {
            Consumer<TurnLimitedConfig.Builder> baseConfig = builder -> builder
                    .maxTurns(100)
                    .stuckThreshold(5);

            TurnLimitedConfig config1 = TurnLimitedConfig.builder()
                    .apply(baseConfig)
                    .timeout(Duration.ofHours(1))
                    .build();

            TurnLimitedConfig config2 = TurnLimitedConfig.builder()
                    .apply(baseConfig)
                    .timeout(Duration.ofMinutes(30))
                    .build();

            assertThat(config1.maxTurns()).isEqualTo(100);
            assertThat(config2.maxTurns()).isEqualTo(100);
            assertThat(config1.timeout()).isEqualTo(Duration.ofHours(1));
            assertThat(config2.timeout()).isEqualTo(Duration.ofMinutes(30));
        }
    }

    @Nested
    @DisplayName("copy()")
    class Copy {

        @Test
        @DisplayName("should create independent builder copy")
        void shouldCreateIndependentBuilderCopy() {
            TurnLimitedConfig.Builder builder1 = TurnLimitedConfig.builder()
                    .maxTurns(100)
                    .tool("read");

            TurnLimitedConfig.Builder builder2 = builder1.copy();
            builder2.maxTurns(200).tool("write");

            TurnLimitedConfig config1 = builder1.build();
            TurnLimitedConfig config2 = builder2.build();

            assertThat(config1.maxTurns()).isEqualTo(100);
            assertThat(config1.tools()).containsExactly("read");
            assertThat(config2.maxTurns()).isEqualTo(200);
            assertThat(config2.tools()).containsExactly("read", "write");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("should reject non-positive maxTurns")
        void shouldRejectNonPositiveMaxTurns() {
            assertThatThrownBy(() -> TurnLimitedConfig.builder().maxTurns(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns must be positive");

            assertThatThrownBy(() -> TurnLimitedConfig.builder().maxTurns(-1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns must be positive");
        }

        @Test
        @DisplayName("should reject null timeout")
        void shouldRejectNullTimeout() {
            assertThatThrownBy(() -> TurnLimitedConfig.builder().timeout(null).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must not be null");
        }

        @Test
        @DisplayName("should reject invalid scoreThreshold")
        void shouldRejectInvalidScoreThreshold() {
            assertThatThrownBy(() -> TurnLimitedConfig.builder().scoreThreshold(-0.1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scoreThreshold must be between 0 and 1");

            assertThatThrownBy(() -> TurnLimitedConfig.builder().scoreThreshold(1.1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scoreThreshold must be between 0 and 1");
        }

        @Test
        @DisplayName("should reject negative stuckThreshold")
        void shouldRejectNegativeStuckThreshold() {
            assertThatThrownBy(() -> TurnLimitedConfig.builder().stuckThreshold(-1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stuckThreshold must not be negative");
        }

        @Test
        @DisplayName("should reject negative costLimit")
        void shouldRejectNegativeCostLimit() {
            assertThatThrownBy(() -> TurnLimitedConfig.builder().costLimit(-1.0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("costLimit must not be negative");
        }

        @Test
        @DisplayName("should reject negative evaluateEveryNTurns")
        void shouldRejectNegativeEvaluateEveryNTurns() {
            assertThatThrownBy(() -> TurnLimitedConfig.builder().evaluateEveryNTurns(-1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evaluateEveryNTurns must not be negative");
        }

        @Test
        @DisplayName("should use default finishToolName for blank input")
        void shouldUseDefaultFinishToolNameForBlankInput() {
            TurnLimitedConfig config1 = TurnLimitedConfig.builder().finishToolName(null).build();
            TurnLimitedConfig config2 = TurnLimitedConfig.builder().finishToolName("  ").build();

            assertThat(config1.finishToolName()).isEqualTo("complete_task");
            assertThat(config2.finishToolName()).isEqualTo("complete_task");
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("should make defensive copy of tools list")
        void shouldMakeDefensiveCopyOfToolsList() {
            List<String> tools = new java.util.ArrayList<>();
            tools.add("read");
            tools.add("write");

            TurnLimitedConfig config = TurnLimitedConfig.builder()
                    .tools(tools)
                    .build();

            // Modify original list
            tools.add("dangerous");

            // Config should not be affected
            assertThat(config.tools()).containsExactly("read", "write");
        }

        @Test
        @DisplayName("tools list should be unmodifiable")
        void toolsListShouldBeUnmodifiable() {
            TurnLimitedConfig config = TurnLimitedConfig.builder()
                    .tools(List.of("read", "write"))
                    .build();

            assertThatThrownBy(() -> config.tools().add("illegal"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Record equals/hashCode")
    class RecordEquality {

        @Test
        @DisplayName("configs with same values should be equal")
        void configsWithSameValuesShouldBeEqual() {
            TurnLimitedConfig config1 = TurnLimitedConfig.builder()
                    .maxTurns(100)
                    .timeout(Duration.ofHours(1))
                    .build();

            TurnLimitedConfig config2 = TurnLimitedConfig.builder()
                    .maxTurns(100)
                    .timeout(Duration.ofHours(1))
                    .build();

            assertThat(config1).isEqualTo(config2);
            assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
        }

        @Test
        @DisplayName("configs with different values should not be equal")
        void configsWithDifferentValuesShouldNotBeEqual() {
            TurnLimitedConfig config1 = TurnLimitedConfig.builder()
                    .maxTurns(100)
                    .build();

            TurnLimitedConfig config2 = TurnLimitedConfig.builder()
                    .maxTurns(200)
                    .build();

            assertThat(config1).isNotEqualTo(config2);
        }
    }
}
