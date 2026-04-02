/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.agents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentLoop.Config")
class AgentLoopConfigTest {

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("should have default max turns of 20")
        void shouldHaveDefaultMaxTurns() {
            AgentLoop.Config config = AgentLoop.Config.builder().build();
            assertThat(config.maxTurns()).isEqualTo(20);
        }

        @Test
        @DisplayName("should have default cost limit of 1.0")
        void shouldHaveDefaultCostLimit() {
            AgentLoop.Config config = AgentLoop.Config.builder().build();
            assertThat(config.costLimit()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should have default command timeout of 30 seconds")
        void shouldHaveDefaultCommandTimeout() {
            AgentLoop.Config config = AgentLoop.Config.builder().build();
            assertThat(config.commandTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("should have default system prompt")
        void shouldHaveDefaultSystemPrompt() {
            AgentLoop.Config config = AgentLoop.Config.builder().build();
            assertThat(config.systemPrompt()).contains("autonomous AI assistant");
            assertThat(config.systemPrompt()).contains("bash");
        }

        @Test
        @DisplayName("should use current directory as default working directory")
        void shouldUseCurrentDirectoryAsDefault() {
            AgentLoop.Config config = AgentLoop.Config.builder().build();
            assertThat(config.workingDirectory()).isEqualTo(Path.of(System.getProperty("user.dir")));
        }
    }

    @Nested
    @DisplayName("Builder methods")
    class BuilderMethods {

        @Test
        @DisplayName("should set max turns")
        void shouldSetMaxTurns() {
            AgentLoop.Config config = AgentLoop.Config.builder()
                    .maxTurns(50)
                    .build();
            assertThat(config.maxTurns()).isEqualTo(50);
        }

        @Test
        @DisplayName("should set cost limit")
        void shouldSetCostLimit() {
            AgentLoop.Config config = AgentLoop.Config.builder()
                    .costLimit(5.0)
                    .build();
            assertThat(config.costLimit()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("should set command timeout")
        void shouldSetCommandTimeout() {
            AgentLoop.Config config = AgentLoop.Config.builder()
                    .commandTimeout(Duration.ofMinutes(2))
                    .build();
            assertThat(config.commandTimeout()).isEqualTo(Duration.ofMinutes(2));
        }

        @Test
        @DisplayName("should set working directory")
        void shouldSetWorkingDirectory() {
            Path customDir = Path.of("/tmp");
            AgentLoop.Config config = AgentLoop.Config.builder()
                    .workingDirectory(customDir)
                    .build();
            assertThat(config.workingDirectory()).isEqualTo(customDir);
        }

        @Test
        @DisplayName("should set custom system prompt")
        void shouldSetSystemPrompt() {
            AgentLoop.Config config = AgentLoop.Config.builder()
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
            assertThatThrownBy(() -> AgentLoop.Config.builder().maxTurns(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns");
        }

        @Test
        @DisplayName("should reject negative max turns")
        void shouldRejectNegativeMaxTurns() {
            assertThatThrownBy(() -> AgentLoop.Config.builder().maxTurns(-1).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTurns");
        }

        @Test
        @DisplayName("should reject zero cost limit")
        void shouldRejectZeroCostLimit() {
            assertThatThrownBy(() -> AgentLoop.Config.builder().costLimit(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("costLimit");
        }

        @Test
        @DisplayName("should reject negative cost limit")
        void shouldRejectNegativeCostLimit() {
            assertThatThrownBy(() -> AgentLoop.Config.builder().costLimit(-1.0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("costLimit");
        }

        @Test
        @DisplayName("should reject null command timeout")
        void shouldRejectNullTimeout() {
            assertThatThrownBy(() -> AgentLoop.Config.builder().commandTimeout(null).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commandTimeout");
        }

        @Test
        @DisplayName("should reject zero command timeout")
        void shouldRejectZeroTimeout() {
            assertThatThrownBy(() -> AgentLoop.Config.builder().commandTimeout(Duration.ZERO).build())
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
            AgentLoop.Config original = AgentLoop.Config.builder()
                    .maxTurns(10)
                    .costLimit(2.5)
                    .commandTimeout(Duration.ofSeconds(60))
                    .workingDirectory(Path.of("/tmp"))
                    .build();

            AgentLoop.Config copy = original.toBuilder().build();

            assertThat(copy).isEqualTo(original);
        }

        @Test
        @DisplayName("should allow modifying copy")
        void shouldAllowModifyingCopy() {
            AgentLoop.Config original = AgentLoop.Config.builder()
                    .maxTurns(10)
                    .build();

            AgentLoop.Config modified = original.toBuilder()
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
            AgentLoop.Config original = AgentLoop.Config.builder()
                    .maxTurns(10)
                    .build();

            AgentLoop.Config modified = original.apply(b -> b.maxTurns(30));

            assertThat(modified.maxTurns()).isEqualTo(30);
            assertThat(original.maxTurns()).isEqualTo(10); // Original unchanged
        }
    }
}
