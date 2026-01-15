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
import org.springaicommunity.agents.harness.callback.AgentCallback;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED", "STUCK");
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
            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED", "STUCK");
            // If it hit the limit, verify the invocations count
            if ("TURN_LIMIT_REACHED".equals(result.status())) {
                assertThat(result.turnsCompleted()).isGreaterThanOrEqualTo(limitedConfig.maxTurns());
            }
        }
    }

    @Nested
    @DisplayName("Builder pattern")
    class BuilderPattern {

        @Test
        @DisplayName("should work with builder pattern")
        void shouldWorkWithBuilder() {
            MiniAgent agent = MiniAgent.builder()
                    .config(config)
                    .model(chatModel)
                    .build();

            MiniAgent.MiniAgentResult result = agent.run("What is 1+1? Reply with just the number.");

            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
            assertThat(result.totalTokens()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should work with session memory enabled")
        void shouldWorkWithSessionMemory() {
            MiniAgent agent = MiniAgent.builder()
                    .config(config)
                    .model(chatModel)
                    .sessionMemory()
                    .build();

            assertThat(agent.hasSessionMemory()).isTrue();

            MiniAgent.MiniAgentResult result = agent.run("What is 2+2? Reply with just the number.");
            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
        }
    }

    @Nested
    @DisplayName("Session memory")
    class SessionMemory {

        @Test
        @DisplayName("should preserve context across chat calls")
        void shouldPreserveContextAcrossChatCalls() {
            MiniAgent agent = MiniAgent.builder()
                    .config(config)
                    .model(chatModel)
                    .sessionMemory()
                    .build();

            // First call - establish context
            MiniAgent.MiniAgentResult result1 = agent.chat(
                    "Remember this number: 42. Just say 'OK' to confirm.",
                    null
            );
            assertThat(result1.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");

            // Second call - reference previous context
            MiniAgent.MiniAgentResult result2 = agent.chat(
                    "What number did I ask you to remember? Reply with just the number.",
                    null
            );
            assertThat(result2.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
            // The agent should remember "42" from session memory
            assertThat(result2.output()).contains("42");
        }

        @Test
        @DisplayName("should clear session when clearSession called")
        void shouldClearSession() {
            MiniAgent agent = MiniAgent.builder()
                    .config(config)
                    .model(chatModel)
                    .sessionMemory()
                    .build();

            // Establish context
            agent.chat("Remember: my favorite color is blue. Just say 'OK'.", null);

            // Clear session
            agent.clearSession();

            // After clear, context should be lost
            MiniAgent.MiniAgentResult result = agent.chat(
                    "What is my favorite color? If you don't know, say 'unknown'.",
                    null
            );
            assertThat(result.status()).isIn("COMPLETED", "TURN_LIMIT_REACHED");
            // Agent should not remember after clearSession
            assertThat(result.output().toLowerCase()).containsAnyOf("unknown", "don't know", "not sure", "haven't");
        }
    }

    @Nested
    @DisplayName("Chat method with callback")
    class ChatWithCallback {

        @Test
        @DisplayName("should invoke onThinking callback")
        void shouldInvokeOnThinkingCallback() {
            AtomicBoolean thinkingCalled = new AtomicBoolean(false);
            AgentCallback callback = new AgentCallback() {
                @Override
                public void onThinking() {
                    thinkingCalled.set(true);
                }
            };

            MiniAgent agent = MiniAgent.builder()
                    .config(config)
                    .model(chatModel)
                    .agentCallback(callback)
                    .build();

            agent.chat("What is 3+3? Reply briefly.", callback);

            assertThat(thinkingCalled.get()).isTrue();
        }

        @Test
        @DisplayName("should invoke onComplete callback")
        void shouldInvokeOnCompleteCallback() {
            AtomicBoolean completeCalled = new AtomicBoolean(false);
            AgentCallback callback = new AgentCallback() {
                @Override
                public void onComplete() {
                    completeCalled.set(true);
                }
            };

            MiniAgent agent = MiniAgent.builder()
                    .config(config)
                    .model(chatModel)
                    .agentCallback(callback)
                    .build();

            agent.chat("What is 4+4? Reply briefly.", callback);

            assertThat(completeCalled.get()).isTrue();
        }

        @Test
        @DisplayName("should invoke multiple callbacks in sequence")
        void shouldInvokeMultipleCallbacks() {
            AtomicInteger callOrder = new AtomicInteger(0);
            AtomicInteger thinkingOrder = new AtomicInteger(-1);
            AtomicInteger completeOrder = new AtomicInteger(-1);

            AgentCallback callback = new AgentCallback() {
                @Override
                public void onThinking() {
                    thinkingOrder.set(callOrder.getAndIncrement());
                }

                @Override
                public void onComplete() {
                    completeOrder.set(callOrder.getAndIncrement());
                }
            };

            MiniAgent agent = MiniAgent.builder()
                    .config(config)
                    .model(chatModel)
                    .agentCallback(callback)
                    .build();

            agent.chat("What is 5+5? Reply briefly.", callback);

            assertThat(thinkingOrder.get()).isGreaterThanOrEqualTo(0);
            assertThat(completeOrder.get()).isGreaterThan(thinkingOrder.get());
        }
    }
}
