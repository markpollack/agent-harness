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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MiniAgentConfig")
class MiniAgentConfigTest {

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("should have default max turns of 20")
        void shouldHaveDefaultMaxTurns() {
            MiniAgentConfig config = MiniAgentConfig.builder().build();
            assertThat(config.maxTurns()).isEqualTo(20);
        }

        @Test
        @DisplayName("should have default cost limit of 1.0")
        void shouldHaveDefaultCostLimit() {
            MiniAgentConfig config = MiniAgentConfig.builder().build();
            assertThat(config.costLimit()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should have default command timeout of 30 seconds")
        void shouldHaveDefaultCommandTimeout() {
            MiniAgentConfig config = MiniAgentConfig.builder().build();
            assertThat(config.commandTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("should have default system prompt")
        void shouldHaveDefaultSystemPrompt() {
            MiniAgentConfig config = MiniAgentConfig.builder().build();
            assertThat(config.systemPrompt()).contains("autonomous AI assistant");
            assertThat(config.systemPrompt()).contains("bash");
        }

        @Test
        @DisplayName("should use current directory as default working directory")
        void shouldUseCurrentDirectoryAsDefault() {
            MiniAgentConfig config = MiniAgentConfig.builder().build();
            assertThat(config.workingDirectory()).isEqualTo(Path.of(System.getProperty("user.dir")));
        }
    }

    @Nested
    @DisplayName("Builder methods")
    class BuilderMethods {

        @Test
        @DisplayName("should set max turns")
        void shouldSetMaxTurns() {
            MiniAgentConfig config = MiniAgentConfig.builder()
                    .maxTurns(50)
                    .build();
            assertThat(config.maxTurns()).isEqualTo(50);
        }

        @Test
        @DisplayName("should set cost limit")
        void shouldSetCostLimit() {
            MiniAgentConfig config = MiniAgentConfig.builder()
                    .costLimit(5.0)
                    .build();
            assertThat(config.costLimit()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("should set command timeout")
        void shouldSetCommandTimeout() {
            MiniAgentConfig config = MiniAgentConfig.builder()
                    .commandTimeout(Duration.ofMinutes(2))
                    .build();
            assertThat(config.commandTimeout()).isEqualTo(Duration.ofMinutes(2));
        }

        @Test
        @DisplayName("should set working directory")
        void shouldSetWorkingDirectory() {
            Path customDir = Path.of("/tmp");
            MiniAgentConfig config = MiniAgentConfig.builder()
                    .workingDirectory(customDir)
                    .build();
            assertThat(config.workingDirectory()).isEqualTo(customDir);
        }

        @Test
        @DisplayName("should set custom system prompt")
        void shouldSetSystemPrompt() {
            MiniAgentConfig config = MiniAgentConfig.builder()
                    .systemPrompt("Custom system prompt")
                    .build();
            assertThat(config.systemPrompt()).isEqualTo("Custom system prompt");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("should reject zero max turns")
        void shouldRejectZeroMaxTurns() {
            assertThatThrownBy(() -> MiniAgentConfig.builder().maxTurns(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns");
        }

        @Test
        @DisplayName("should reject negative max turns")
        void shouldRejectNegativeMaxTurns() {
            assertThatThrownBy(() -> MiniAgentConfig.builder().maxTurns(-1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns");
        }

        @Test
        @DisplayName("should reject zero cost limit")
        void shouldRejectZeroCostLimit() {
            assertThatThrownBy(() -> MiniAgentConfig.builder().costLimit(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("costLimit");
        }

        @Test
        @DisplayName("should reject negative cost limit")
        void shouldRejectNegativeCostLimit() {
            assertThatThrownBy(() -> MiniAgentConfig.builder().costLimit(-1.0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("costLimit");
        }

        @Test
        @DisplayName("should reject null command timeout")
        void shouldRejectNullTimeout() {
            assertThatThrownBy(() -> MiniAgentConfig.builder().commandTimeout(null).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commandTimeout");
        }

        @Test
        @DisplayName("should reject zero command timeout")
        void shouldRejectZeroTimeout() {
            assertThatThrownBy(() -> MiniAgentConfig.builder().commandTimeout(Duration.ZERO).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commandTimeout");
        }
    }

    @Nested
    @DisplayName("toBuilder")
    class ToBuilder {

        @Test
        @DisplayName("should create copy with same values")
        void shouldCreateCopyWithSameValues() {
            MiniAgentConfig original = MiniAgentConfig.builder()
                    .maxTurns(10)
                    .costLimit(2.5)
                    .commandTimeout(Duration.ofSeconds(60))
                    .workingDirectory(Path.of("/tmp"))
                    .build();

            MiniAgentConfig copy = original.toBuilder().build();

            assertThat(copy).isEqualTo(original);
        }

        @Test
        @DisplayName("should allow modifying copy")
        void shouldAllowModifyingCopy() {
            MiniAgentConfig original = MiniAgentConfig.builder()
                    .maxTurns(10)
                    .build();

            MiniAgentConfig modified = original.toBuilder()
                    .maxTurns(20)
                    .build();

            assertThat(original.maxTurns()).isEqualTo(10);
            assertThat(modified.maxTurns()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("should apply customizer function")
        void shouldApplyCustomizer() {
            MiniAgentConfig original = MiniAgentConfig.builder()
                    .maxTurns(10)
                    .build();

            MiniAgentConfig modified = original.apply(b -> b.maxTurns(30));

            assertThat(modified.maxTurns()).isEqualTo(30);
            assertThat(original.maxTurns()).isEqualTo(10); // Original unchanged
        }
    }
}
