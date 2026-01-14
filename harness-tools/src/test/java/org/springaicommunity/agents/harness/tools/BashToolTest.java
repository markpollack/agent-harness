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
package org.springaicommunity.agents.harness.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BashToolTest {

    @TempDir
    Path tempDir;

    BashTool bashTool;

    @BeforeEach
    void setUp() {
        bashTool = new BashTool(tempDir);
    }

    @Test
    void executesSimpleCommand() {
        String result = bashTool.bash("echo hello", null);

        assertThat(result).contains("hello");
        assertThat(result).contains("[Exit code: 0]");
    }

    @Test
    void capturesExitCode() {
        String result = bashTool.bash("exit 42", null);

        assertThat(result).contains("[Exit code: 42]");
    }

    @Test
    void executesInWorkingDirectory() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "content");

        String result = bashTool.bash("cat test.txt", null);

        assertThat(result).contains("content");
    }

    @Test
    void capturesStderr() {
        String result = bashTool.bash("echo error >&2", null);

        assertThat(result).contains("error");
    }

    @Test
    void returnsErrorForEmptyCommand() {
        String result = bashTool.bash("", null);

        assertThat(result).startsWith("Error: command must not be empty");
    }

    @Test
    void handlesCommandWithPipes() {
        String result = bashTool.bash("echo 'hello world' | wc -w", null);

        assertThat(result).contains("2");
    }

    @Test
    void handlesMultilineOutput() {
        String result = bashTool.bash("echo -e 'line1\\nline2\\nline3'", null);

        assertThat(result).contains("line1");
        assertThat(result).contains("line2");
        assertThat(result).contains("line3");
    }

    @Test
    void respectsCustomTimeout() {
        // Short command should complete within 1 second timeout
        String result = bashTool.bash("echo quick", 1);

        assertThat(result).contains("quick");
        assertThat(result).contains("[Exit code: 0]");
    }
}
