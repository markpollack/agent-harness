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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;

/**
 * Tool for finding files using glob patterns.
 */
public class GlobTool {

    private static final Logger log = LoggerFactory.getLogger(GlobTool.class);

    private static final int MAX_RESULTS = 500;

    private final Path basePath;

    public GlobTool(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath, "basePath must not be null");
    }

    /**
     * Finds files matching a glob pattern.
     */
    @Tool(description = "Find files matching a glob pattern (e.g., **/*.java). " +
            "Returns paths sorted by modification time.")
    public String glob(
            @ToolParam(description = "Glob pattern to match (e.g., **/*.java)") String pattern,
            @ToolParam(description = "Directory to search in", required = false) String path) {

        if (pattern == null || pattern.isEmpty()) {
            return "Error: pattern must not be empty";
        }

        Path searchDir = (path != null && !path.isEmpty()) ? resolvePath(path) : basePath;

        if (!Files.exists(searchDir)) {
            return "Error: Directory not found: " + searchDir;
        }
        if (!Files.isDirectory(searchDir)) {
            return "Error: Path is not a directory: " + searchDir;
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<PathWithTime> matches = new ArrayList<>();

            Files.walkFileTree(searchDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relativePath = searchDir.relativize(file);
                    if (matcher.matches(relativePath)) {
                        matches.add(new PathWithTime(file, attrs.lastModifiedTime().toMillis()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            matches.sort(Comparator.comparingLong(PathWithTime::modTime).reversed());

            StringBuilder result = new StringBuilder();
            int count = Math.min(matches.size(), MAX_RESULTS);
            for (int i = 0; i < count; i++) {
                result.append(matches.get(i).path()).append("\n");
            }

            if (matches.size() > MAX_RESULTS) {
                result.append("\n[Showing ").append(MAX_RESULTS)
                        .append(" of ").append(matches.size()).append(" matches]");
            }

            return result.toString().trim();

        } catch (PatternSyntaxException e) {
            return "Error: Invalid glob pattern: " + e.getMessage();
        } catch (IOException e) {
            log.error("Failed to search {}: {}", searchDir, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private Path resolvePath(String filePath) {
        Path path = Path.of(filePath);
        return path.isAbsolute() ? path : basePath.resolve(path);
    }

    private record PathWithTime(Path path, long modTime) {}
}
