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

class ReadToolTest {

    @TempDir
    Path tempDir;

    ReadTool readTool;

    @BeforeEach
    void setUp() {
        readTool = new ReadTool(tempDir);
    }

    @Test
    void readsFileWithLineNumbers() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line one\nline two\nline three");

        String result = readTool.read(file.toString(), null, null);

        assertThat(result).contains("1\tline one");
        assertThat(result).contains("2\tline two");
        assertThat(result).contains("3\tline three");
    }

    @Test
    void readsFileWithOffset() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line one\nline two\nline three\nline four");

        String result = readTool.read(file.toString(), 2, null);

        assertThat(result).doesNotContain("line one");
        assertThat(result).contains("2\tline two");
        assertThat(result).contains("3\tline three");
    }

    @Test
    void readsFileWithLimit() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line one\nline two\nline three\nline four");

        String result = readTool.read(file.toString(), null, 2);

        assertThat(result).contains("1\tline one");
        assertThat(result).contains("2\tline two");
        assertThat(result).doesNotContain("line three");
        assertThat(result).contains("[Showing lines 1-2 of 4]");
    }

    @Test
    void readsFileWithOffsetAndLimit() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "a\nb\nc\nd\ne");

        String result = readTool.read(file.toString(), 2, 2);

        assertThat(result).doesNotContain("1\ta");
        assertThat(result).contains("2\tb");
        assertThat(result).contains("3\tc");
        assertThat(result).doesNotContain("d");
    }

    @Test
    void returnsErrorForMissingFile() {
        String result = readTool.read("nonexistent.txt", null, null);

        assertThat(result).startsWith("Error: File not found");
    }

    @Test
    void returnsErrorForDirectory() {
        String result = readTool.read(tempDir.toString(), null, null);

        assertThat(result).startsWith("Error: Path is a directory");
    }

    @Test
    void handlesEmptyFile() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        String result = readTool.read(file.toString(), null, null);

        // Empty file has 0 lines, so offset 0 is beyond end
        assertThat(result).contains("0 lines");
    }

    @Test
    void resolvesRelativePath() throws IOException {
        Path file = tempDir.resolve("subdir/test.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");

        String result = readTool.read("subdir/test.txt", null, null);

        assertThat(result).contains("content");
    }
}
