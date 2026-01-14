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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Tool for editing files using exact string replacement.
 */
public class EditTool {

    private static final Logger log = LoggerFactory.getLogger(EditTool.class);

    private final Path basePath;

    public EditTool(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath, "basePath must not be null");
    }

    /**
     * Replaces text in a file.
     */
    @Tool(description = "Replace text in a file. oldText must be unique unless replaceAll is true.")
    public String edit(
            @ToolParam(description = "Path to the file") String filePath,
            @ToolParam(description = "Text to find and replace") String oldText,
            @ToolParam(description = "Replacement text") String newText,
            @ToolParam(description = "Replace all occurrences", required = false) Boolean replaceAll) {

        if (oldText == null || oldText.isEmpty()) {
            return "Error: oldText must not be empty";
        }
        if (newText == null) {
            return "Error: newText must not be null";
        }
        if (oldText.equals(newText)) {
            return "Error: newText must be different from oldText";
        }

        Path path = resolvePath(filePath);

        if (!Files.exists(path)) {
            return "Error: File not found: " + filePath;
        }
        if (Files.isDirectory(path)) {
            return "Error: Path is a directory: " + filePath;
        }

        try {
            String content = Files.readString(path);
            int count = countOccurrences(content, oldText);

            if (count == 0) {
                return "Error: Text not found in file";
            }

            boolean doReplaceAll = replaceAll != null && replaceAll;
            if (!doReplaceAll && count > 1) {
                return "Error: Text appears " + count + " times. Use replaceAll=true or make text unique.";
            }

            String newContent = doReplaceAll
                    ? content.replace(oldText, newText)
                    : content.replaceFirst(java.util.regex.Pattern.quote(oldText),
                            java.util.regex.Matcher.quoteReplacement(newText));

            Files.writeString(path, newContent);
            log.info("Edited {}: replaced {} occurrence(s)", path, doReplaceAll ? count : 1);

            return "Replaced " + (doReplaceAll ? count : 1) + " occurrence(s) in " + filePath;

        } catch (IOException e) {
            log.error("Failed to edit {}: {}", path, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private int countOccurrences(String content, String search) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }

    private Path resolvePath(String filePath) {
        Path path = Path.of(filePath);
        return path.isAbsolute() ? path : basePath.resolve(path);
    }
}
