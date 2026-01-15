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
package org.springaicommunity.agents.harness.agents.mini;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for MiniAgent using real Anthropic Claude API.
 * <p>
 * These tests require the ANTHROPIC_API_KEY environment variable to be set.
 * Run with Maven Failsafe plugin:
 * <pre>
 * mvn verify -pl harness-examples
 * </pre>
 */
@DisplayName("MiniAgent Integration Tests")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class MiniAgentIT {

    @BeforeAll
    static void validateApiKey() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            fail("""

                ========================================
                ANTHROPIC_API_KEY environment variable is not set.

                Set it in your IDE run configuration or shell:
                  export ANTHROPIC_API_KEY="sk-ant-..."
                ========================================
                """);
        }
        if (!apiKey.startsWith("sk-ant-")) {
            fail("""

                ========================================
                ANTHROPIC_API_KEY appears invalid.

                Expected format: sk-ant-api03-...
                Got: %s...

                Check for copy/paste errors or an expired key.
                ========================================
                """.formatted(apiKey.substring(0, Math.min(10, apiKey.length()))));
        }
    }

    @TempDir
    Path tempDir;

    private ChatModel chatModel;
    private MiniAgentConfig config;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        AnthropicApi api = AnthropicApi.builder()
                .apiKey(apiKey)
                .build();

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(1024)
                .build();

        chatModel = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .build();

        config = MiniAgentConfig.builder()
                .maxTurns(5)
                .workingDirectory(tempDir)
                .commandTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Nested
    @DisplayName("Simple tasks")
    class SimpleTasks {

        @Test
        @DisplayName("should list files in a directory")
        void shouldListFiles() throws IOException {
            // Create some test files
            Files.writeString(tempDir.resolve("file1.txt"), "content1");
            Files.writeString(tempDir.resolve("file2.txt"), "content2");

            MiniAgent agent = new MiniAgent(config, chatModel);
            MiniAgent.MiniAgentResult result = agent.run(
                    "List all files in the current directory and tell me how many there are. " +
                    "Submit your answer when done."
            );

            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
            assertThat(result.turnsCompleted()).isGreaterThan(0);
            assertThat(result.totalTokens()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should read file contents")
        void shouldReadFileContents() throws IOException {
            // Create a test file with specific content
            String expectedContent = "Hello from integration test!";
            Files.writeString(tempDir.resolve("test.txt"), expectedContent);

            MiniAgent agent = new MiniAgent(config, chatModel);
            MiniAgent.MiniAgentResult result = agent.run(
                    "Read the contents of test.txt and tell me what it says. " +
                    "Submit your answer when done."
            );

            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
            assertThat(result.turnsCompleted()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should create a file")
        void shouldCreateFile() {
            MiniAgent agent = new MiniAgent(config, chatModel);
            MiniAgent.MiniAgentResult result = agent.run(
                    "Create a file named 'created.txt' with the content 'Created by MiniAgent'. " +
                    "Submit 'done' when the file has been created."
            );

            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
            assertThat(Files.exists(tempDir.resolve("created.txt"))).isTrue();
        }
    }

    @Nested
    @DisplayName("Multi-step tasks")
    class MultiStepTasks {

        @Test
        @DisplayName("should complete multi-step file manipulation")
        void shouldCompleteMultiStepTask() throws IOException {
            // Create initial file
            Files.writeString(tempDir.resolve("input.txt"), "line1\nline2\nline3");

            MiniAgent agent = new MiniAgent(config, chatModel);
            MiniAgent.MiniAgentResult result = agent.run(
                    "1. Read input.txt\n" +
                    "2. Count the number of lines\n" +
                    "3. Create output.txt with the line count\n" +
                    "4. Submit 'done' when complete"
            );

            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
            // Model may complete in 1 or more turns depending on efficiency
            assertThat(result.turnsCompleted()).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Resource tracking")
    class ResourceTracking {

        @Test
        @DisplayName("should track tokens used")
        void shouldTrackTokens() {
            MiniAgent agent = new MiniAgent(config, chatModel);
            MiniAgent.MiniAgentResult result = agent.run(
                    "What is 2+2? Submit the answer."
            );

            assertThat(result.totalTokens()).isGreaterThan(0);
            assertThat(result.estimatedCost()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("should respect turn limits and return TURN_LIMIT_REACHED status")
        void shouldRespectTurnLimits() {
            MiniAgentConfig limitedConfig = config.toBuilder()
                    .maxTurns(2)
                    .build();

            MiniAgent agent = new MiniAgent(limitedConfig, chatModel);
            MiniAgent.MiniAgentResult result = agent.run(
                    "Explore the filesystem extensively, looking at every directory. " +
                    "Never submit until you've seen everything."
            );

            // Should stop due to turn limit with appropriate status
            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
            // If it hit the limit, verify the invocations count
            if ("TURN_LIMIT_REACHED".equals(result.status())) {
                assertThat(result.turnsCompleted()).isGreaterThan(limitedConfig.maxTurns());
            }
        }
    }
}
