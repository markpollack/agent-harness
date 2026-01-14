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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MiniAgentTools")
class MiniAgentToolsTest {

    @TempDir
    Path tempDir;

    private MiniAgentTools tools;

    @BeforeEach
    void setUp() {
        tools = new MiniAgentTools(tempDir, Duration.ofSeconds(5));
    }

    @Nested
    @DisplayName("bash tool")
    class BashTool {

        @Test
        @DisplayName("should execute simple echo command")
        void shouldExecuteEchoCommand() {
            String result = tools.bash("echo 'hello world'");

            assertThat(result).contains("hello world");
            assertThat(result).contains("<returncode>0</returncode>");
        }

        @Test
        @DisplayName("should capture exit code for failed commands")
        void shouldCaptureExitCode() {
            String result = tools.bash("exit 42");

            assertThat(result).contains("<returncode>42</returncode>");
        }

        @Test
        @DisplayName("should execute in working directory")
        void shouldExecuteInWorkingDirectory() {
            String result = tools.bash("pwd");

            assertThat(result).contains(tempDir.toString());
            assertThat(result).contains("<returncode>0</returncode>");
        }

        @Test
        @DisplayName("should capture stderr in output")
        void shouldCaptureStderr() {
            String result = tools.bash("echo 'error' >&2");

            assertThat(result).contains("error");
            assertThat(result).contains("<returncode>0</returncode>");
        }

        @Test
        @DisplayName("should handle command timeout")
        void shouldHandleTimeout() {
            MiniAgentTools shortTimeoutTools = new MiniAgentTools(tempDir, Duration.ofMillis(100));

            String result = shortTimeoutTools.bash("sleep 5");

            assertThat(result).contains("<timeout>");
        }

        @Test
        @DisplayName("should handle file operations")
        void shouldHandleFileOperations() throws IOException {
            // Create a file
            String createResult = tools.bash("echo 'test content' > test.txt");
            assertThat(createResult).contains("<returncode>0</returncode>");

            // Verify file was created
            assertThat(Files.exists(tempDir.resolve("test.txt"))).isTrue();

            // Read the file
            String readResult = tools.bash("cat test.txt");
            assertThat(readResult).contains("test content");
            assertThat(readResult).contains("<returncode>0</returncode>");
        }

        @Test
        @DisplayName("should handle pipe operations")
        void shouldHandlePipeOperations() {
            String result = tools.bash("echo 'hello world' | grep 'world'");

            assertThat(result).contains("hello world");
            assertThat(result).contains("<returncode>0</returncode>");
        }

        @Test
        @DisplayName("should handle command not found")
        void shouldHandleCommandNotFound() {
            String result = tools.bash("nonexistent_command_xyz");

            assertThat(result).contains("<returncode>");
            // Exit code will be non-zero
            assertThat(result).doesNotContain("<returncode>0</returncode>");
        }

        @Test
        @DisplayName("should handle multiline output")
        void shouldHandleMultilineOutput() {
            String result = tools.bash("echo -e 'line1\\nline2\\nline3'");

            assertThat(result).contains("line1");
            assertThat(result).contains("line2");
            assertThat(result).contains("line3");
            assertThat(result).contains("<returncode>0</returncode>");
        }
    }

    @Nested
    @DisplayName("submit tool")
    class SubmitTool {

        @Test
        @DisplayName("should return the answer as-is")
        void shouldReturnAnswer() {
            String result = tools.submit("The answer is 42");

            assertThat(result).isEqualTo("The answer is 42");
        }

        @Test
        @DisplayName("should handle multiline answers")
        void shouldHandleMultilineAnswers() {
            String answer = "Line 1\nLine 2\nLine 3";
            String result = tools.submit(answer);

            assertThat(result).isEqualTo(answer);
        }

        @Test
        @DisplayName("should handle empty answer")
        void shouldHandleEmptyAnswer() {
            String result = tools.submit("");

            assertThat(result).isEmpty();
        }
    }
}
