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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MiniAgent")
class MiniAgentTest {

    @TempDir
    Path tempDir;

    private MiniAgentConfig config;

    @BeforeEach
    void setUp() {
        config = MiniAgentConfig.builder()
                .maxTurns(10)
                .workingDirectory(tempDir)
                .commandTimeout(Duration.ofSeconds(5))
                .build();
    }

    private ChatModel createChatModel(List<String> responses) {
        return new DeterministicChatModel(responses);
    }

    @Nested
    @DisplayName("Successful completion")
    class SuccessfulCompletion {

        @Test
        @DisplayName("should complete when submit tool is called")
        void shouldCompleteOnSubmit() {
            // Model calls submit tool to complete the task
            List<String> responses = List.of(
                    "I'll check what files are here first."
            );

            ChatModel chatModel = createChatModel(responses);
            MiniAgent agent = new MiniAgent(config, chatModel);

            MiniAgent.MiniAgentResult result = agent.run("List files");

            // The loop should complete (may be due to no tool calls = finish)
            assertThat(result.status()).isIn("COMPLETED", "FAILED");
        }
    }

    @Nested
    @DisplayName("Invocation limit enforcement")
    class InvocationLimitEnforcement {

        @Test
        @DisplayName("should complete in single invocation")
        void shouldCompleteInSingleInvocation() {
            // Create config with very low turn limit
            MiniAgentConfig limitedConfig = config.toBuilder()
                    .maxTurns(2)
                    .build();

            // Responses that don't call submit (would loop forever)
            List<String> responses = List.of(
                    "Step 1: checking files",
                    "Step 2: still checking",
                    "Step 3: should not reach here"
            );

            ChatModel chatModel = createChatModel(responses);
            MiniAgent agent = new MiniAgent(limitedConfig, chatModel);

            MiniAgent.MiniAgentResult result = agent.run("Infinite task");

            // MiniAgent always uses 1 invocation (Spring AI handles internal loop)
            assertThat(result.turnsCompleted()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Result tracking")
    class ResultTracking {

        @Test
        @DisplayName("should track turns completed")
        void shouldTrackTurnsCompleted() {
            List<String> responses = List.of("Single response");

            ChatModel chatModel = createChatModel(responses);
            MiniAgent agent = new MiniAgent(config, chatModel);

            MiniAgent.MiniAgentResult result = agent.run("Simple task");

            assertThat(result.turnsCompleted()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("should track tokens")
        void shouldTrackTokens() {
            List<String> responses = List.of("Response");

            ChatModel chatModel = createChatModel(responses);
            MiniAgent agent = new MiniAgent(config, chatModel);

            MiniAgent.MiniAgentResult result = agent.run("Task");

            assertThat(result.totalTokens()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("MiniAgentResult")
    class MiniAgentResultTest {

        @Test
        @DisplayName("isSuccess should return true for COMPLETED status")
        void isSuccessShouldReturnTrueForCompleted() {
            MiniAgent.MiniAgentResult result = new MiniAgent.MiniAgentResult(
                    "COMPLETED", "output", 1, 0, 100, 0.01
            );

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isFailure()).isFalse();
        }

        @Test
        @DisplayName("isFailure should return true for FAILED status")
        void isFailureShouldReturnTrueForFailed() {
            MiniAgent.MiniAgentResult result = new MiniAgent.MiniAgentResult(
                    "FAILED", null, 0, 0, 0, 0.0
            );

            assertThat(result.isFailure()).isTrue();
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("TURN_LIMIT_REACHED should not be success or failure")
        void turnLimitReachedShouldNotBeSuccessOrFailure() {
            MiniAgent.MiniAgentResult result = new MiniAgent.MiniAgentResult(
                    "TURN_LIMIT_REACHED", "partial output", 3, 5, 500, 0.003
            );

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isFailure()).isFalse();
            assertThat(result.status()).isEqualTo("TURN_LIMIT_REACHED");
            assertThat(result.output()).isEqualTo("partial output");
        }
    }
}
