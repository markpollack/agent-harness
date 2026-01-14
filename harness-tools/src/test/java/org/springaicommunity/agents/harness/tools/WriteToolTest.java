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

class WriteToolTest {

    @TempDir
    Path tempDir;

    WriteTool writeTool;

    @BeforeEach
    void setUp() {
        writeTool = new WriteTool(tempDir);
    }

    @Test
    void createsNewFile() throws IOException {
        Path file = tempDir.resolve("new.txt");

        String result = writeTool.write(file.toString(), "hello world");

        assertThat(result).startsWith("Created");
        assertThat(Files.readString(file)).isEqualTo("hello world");
    }

    @Test
    void overwritesExistingFile() throws IOException {
        Path file = tempDir.resolve("existing.txt");
        Files.writeString(file, "old content");

        String result = writeTool.write(file.toString(), "new content");

        assertThat(result).startsWith("Updated");
        assertThat(Files.readString(file)).isEqualTo("new content");
    }

    @Test
    void reportsLineCount() {
        Path file = tempDir.resolve("lines.txt");

        String result = writeTool.write(file.toString(), "line1\nline2\nline3");

        assertThat(result).contains("3 lines");
    }

    @Test
    void returnsErrorForMissingParentDirectory() {
        Path file = tempDir.resolve("nonexistent/dir/file.txt");

        String result = writeTool.write(file.toString(), "content");

        assertThat(result).startsWith("Error: Parent directory does not exist");
    }

    @Test
    void resolvesRelativePath() throws IOException {
        Path subdir = tempDir.resolve("subdir");
        Files.createDirectories(subdir);

        String result = writeTool.write("subdir/file.txt", "content");

        assertThat(result).startsWith("Created");
        assertThat(Files.readString(subdir.resolve("file.txt"))).isEqualTo("content");
    }
}
