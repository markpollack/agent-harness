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
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tool for searching file contents using ripgrep.
 */
public class GrepTool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_LINES = 500;

    private final Path basePath;

    public GrepTool(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath, "basePath must not be null");
    }

    /**
     * Searches for a pattern in files using ripgrep.
     */
    @Tool(description = "Search for a regex pattern in files. Uses ripgrep for fast searching.")
    public String grep(
            @ToolParam(description = "Regex pattern to search for") String pattern,
            @ToolParam(description = "Directory or file to search", required = false) String path,
            @ToolParam(description = "File type (e.g., java, py, js)", required = false) String fileType,
            @ToolParam(description = "Case-insensitive search", required = false) Boolean ignoreCase) {

        if (pattern == null || pattern.isEmpty()) {
            return "Error: pattern must not be empty";
        }

        Path searchPath = (path != null && !path.isEmpty()) ? resolvePath(path) : basePath;

        if (!Files.exists(searchPath)) {
            return "Error: Path not found: " + searchPath;
        }

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("rg");
            cmd.add("--line-number");
            cmd.add("--color=never");

            if (ignoreCase != null && ignoreCase) {
                cmd.add("--ignore-case");
            }
            if (fileType != null && !fileType.isEmpty()) {
                cmd.add("--type");
                cmd.add(fileType);
            }

            cmd.add(pattern);
            cmd.add(searchPath.toString());

            ProcessResult result = new ProcessExecutor()
                    .command(cmd)
                    .directory(basePath.toFile())
                    .timeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .readOutput(true)
                    .redirectErrorStream(true)
                    .execute();

            String output = result.outputUTF8();
            int exitCode = result.getExitValue();

            if (exitCode == 1) {
                return "No matches found for: " + pattern;
            }
            if (exitCode > 1) {
                return "Error: " + output;
            }

            // Limit output
            String[] lines = output.split("\n");
            if (lines.length > MAX_LINES) {
                StringBuilder limited = new StringBuilder();
                for (int i = 0; i < MAX_LINES; i++) {
                    limited.append(lines[i]).append("\n");
                }
                limited.append("\n[Showing ").append(MAX_LINES)
                        .append(" of ").append(lines.length).append(" matches]");
                return limited.toString();
            }

            return output;

        } catch (TimeoutException e) {
            return "Error: Search timed out after " + TIMEOUT.toSeconds() + " seconds";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Search failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private Path resolvePath(String filePath) {
        Path path = Path.of(filePath);
        return path.isAbsolute() ? path : basePath.resolve(path);
    }
}
