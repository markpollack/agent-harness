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

class GlobToolTest {

    @TempDir
    Path tempDir;

    GlobTool globTool;

    @BeforeEach
    void setUp() {
        globTool = new GlobTool(tempDir);
    }

    @Test
    void findsFilesMatchingPattern() throws IOException {
        Files.writeString(tempDir.resolve("file1.txt"), "");
        Files.writeString(tempDir.resolve("file2.txt"), "");
        Files.writeString(tempDir.resolve("file.java"), "");

        String result = globTool.glob("*.txt", null);

        assertThat(result).contains("file1.txt");
        assertThat(result).contains("file2.txt");
        assertThat(result).doesNotContain("file.java");
    }

    @Test
    void findsFilesInSubdirectories() throws IOException {
        Path subdir = tempDir.resolve("src/main/java");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("App.java"), "");
        Files.writeString(subdir.resolve("Util.java"), "");

        String result = globTool.glob("**/*.java", null);

        assertThat(result).contains("App.java");
        assertThat(result).contains("Util.java");
    }

    @Test
    void searchesInSpecifiedPath() throws IOException {
        Path subdir = tempDir.resolve("subdir");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("file.txt"), "");
        Files.writeString(tempDir.resolve("root.txt"), "");

        String result = globTool.glob("*.txt", "subdir");

        assertThat(result).contains("file.txt");
        assertThat(result).doesNotContain("root.txt");
    }

    @Test
    void returnsEmptyForNoMatches() throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "");

        String result = globTool.glob("*.java", null);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsErrorForEmptyPattern() {
        String result = globTool.glob("", null);

        assertThat(result).startsWith("Error: pattern must not be empty");
    }

    @Test
    void returnsErrorForNonexistentPath() {
        String result = globTool.glob("*.txt", "nonexistent");

        assertThat(result).startsWith("Error: Directory not found");
    }

    @Test
    void returnsErrorForFileAsPath() throws IOException {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "");

        String result = globTool.glob("*.txt", file.toString());

        assertThat(result).startsWith("Error: Path is not a directory");
    }
}
