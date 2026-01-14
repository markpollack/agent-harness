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

class EditToolTest {

    @TempDir
    Path tempDir;

    EditTool editTool;

    @BeforeEach
    void setUp() {
        editTool = new EditTool(tempDir);
    }

    @Test
    void replacesUniqueText() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");

        String result = editTool.edit(file.toString(), "world", "universe", null);

        assertThat(result).contains("Replaced 1 occurrence");
        assertThat(Files.readString(file)).isEqualTo("hello universe");
    }

    @Test
    void replacesAllOccurrences() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "foo bar foo baz foo");

        String result = editTool.edit(file.toString(), "foo", "qux", true);

        assertThat(result).contains("Replaced 3 occurrence");
        assertThat(Files.readString(file)).isEqualTo("qux bar qux baz qux");
    }

    @Test
    void returnsErrorForNonUniqueTextWithoutReplaceAll() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "foo bar foo");

        String result = editTool.edit(file.toString(), "foo", "qux", false);

        assertThat(result).contains("appears 2 times");
        assertThat(Files.readString(file)).isEqualTo("foo bar foo"); // unchanged
    }

    @Test
    void returnsErrorForMissingText() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");

        String result = editTool.edit(file.toString(), "xyz", "abc", null);

        assertThat(result).contains("Text not found");
    }

    @Test
    void returnsErrorForMissingFile() {
        String result = editTool.edit("nonexistent.txt", "a", "b", null);

        assertThat(result).startsWith("Error: File not found");
    }

    @Test
    void returnsErrorForEmptyOldText() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");

        String result = editTool.edit(file.toString(), "", "new", null);

        assertThat(result).contains("oldText must not be empty");
    }

    @Test
    void returnsErrorForSameText() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        String result = editTool.edit(file.toString(), "hello", "hello", null);

        assertThat(result).contains("newText must be different");
    }

    @Test
    void handlesSpecialRegexCharacters() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "price: $100.00");

        String result = editTool.edit(file.toString(), "$100.00", "$200.00", null);

        assertThat(result).contains("Replaced 1 occurrence");
        assertThat(Files.readString(file)).isEqualTo("price: $200.00");
    }
}
