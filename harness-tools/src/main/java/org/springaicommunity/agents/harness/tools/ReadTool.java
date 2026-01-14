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
import java.util.List;
import java.util.Objects;

/**
 * Tool for reading file contents with line numbers.
 */
public class ReadTool {

    private static final Logger log = LoggerFactory.getLogger(ReadTool.class);

    private static final int MAX_LINES = 2000;
    private static final int MAX_LINE_LENGTH = 2000;

    private final Path basePath;

    public ReadTool(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath, "basePath must not be null");
    }

    /**
     * Reads a file and returns its contents with line numbers.
     */
    @Tool(description = "Read file contents with line numbers. Use offset/limit for large files.")
    public String read(
            @ToolParam(description = "Path to the file") String filePath,
            @ToolParam(description = "Starting line number (1-based)", required = false) Integer offset,
            @ToolParam(description = "Max lines to read", required = false) Integer limit) {

        Path path = resolvePath(filePath);

        if (!Files.exists(path)) {
            return "Error: File not found: " + filePath;
        }
        if (Files.isDirectory(path)) {
            return "Error: Path is a directory: " + filePath;
        }

        try {
            List<String> lines = Files.readAllLines(path);
            int start = (offset != null && offset > 0) ? offset - 1 : 0;
            int count = (limit != null && limit > 0) ? limit : MAX_LINES;
            int end = Math.min(start + count, lines.size());

            if (start >= lines.size()) {
                return "File has " + lines.size() + " lines, offset " + offset + " is beyond end";
            }

            StringBuilder result = new StringBuilder();
            int width = String.valueOf(end).length();

            for (int i = start; i < end; i++) {
                String line = lines.get(i);
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + "...";
                }
                result.append(String.format("%" + width + "d\t%s%n", i + 1, line));
            }

            if (end < lines.size()) {
                result.append("\n[Showing lines ").append(start + 1)
                        .append("-").append(end)
                        .append(" of ").append(lines.size()).append("]");
            }

            return result.toString();

        } catch (IOException e) {
            log.error("Failed to read {}: {}", path, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private Path resolvePath(String filePath) {
        Path path = Path.of(filePath);
        return path.isAbsolute() ? path : basePath.resolve(path);
    }
}
