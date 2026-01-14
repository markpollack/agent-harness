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
 * Tool for writing file contents.
 */
public class WriteTool {

    private static final Logger log = LoggerFactory.getLogger(WriteTool.class);

    private final Path basePath;

    public WriteTool(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath, "basePath must not be null");
    }

    /**
     * Writes content to a file, creating or overwriting it.
     */
    @Tool(description = "Write content to a file. Creates if new, overwrites if exists.")
    public String write(
            @ToolParam(description = "Path to the file") String filePath,
            @ToolParam(description = "Content to write") String content) {

        Path path = resolvePath(filePath);

        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            return "Error: Parent directory does not exist: " + parent;
        }

        try {
            boolean existed = Files.exists(path);
            Files.writeString(path, content);

            String action = existed ? "Updated" : "Created";
            int lines = content.split("\n", -1).length;
            log.info("{} file: {} ({} lines)", action, path, lines);

            return action + " " + filePath + " (" + lines + " lines)";

        } catch (IOException e) {
            log.error("Failed to write {}: {}", path, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private Path resolvePath(String filePath) {
        Path path = Path.of(filePath);
        return path.isAbsolute() ? path : basePath.resolve(path);
    }
}
