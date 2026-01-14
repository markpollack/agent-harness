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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tool for executing bash commands.
 */
public class BashTool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_OUTPUT_LENGTH = 30000;

    private final Path workingDirectory;
    private final Duration defaultTimeout;

    public BashTool(Path workingDirectory) {
        this(workingDirectory, DEFAULT_TIMEOUT);
    }

    public BashTool(Path workingDirectory, Duration defaultTimeout) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : DEFAULT_TIMEOUT;
    }

    /**
     * Executes a bash command.
     */
    @Tool(description = "Execute a bash command and return its output.")
    public String bash(
            @ToolParam(description = "The command to execute") String command,
            @ToolParam(description = "Timeout in seconds (max 600)", required = false) Integer timeoutSeconds) {

        if (command == null || command.isEmpty()) {
            return "Error: command must not be empty";
        }

        Duration timeout = calculateTimeout(timeoutSeconds);
        log.debug("Executing: {} (timeout={}s)", command, timeout.toSeconds());

        try {
            ProcessResult result = new ProcessExecutor()
                    .command("bash", "-c", command)
                    .directory(workingDirectory.toFile())
                    .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .readOutput(true)
                    .redirectErrorStream(true)
                    .execute();

            String output = result.outputUTF8();
            int exitCode = result.getExitValue();

            log.debug("Command completed with exit code {}", exitCode);

            return formatOutput(output, exitCode);

        } catch (TimeoutException e) {
            log.warn("Command timed out: {}", command);
            return "Error: Command timed out after " + timeout.toSeconds() + " seconds";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Command failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private Duration calculateTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return defaultTimeout;
        }
        Duration requested = Duration.ofSeconds(timeoutSeconds);
        return requested.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : requested;
    }

    private String formatOutput(String output, int exitCode) {
        StringBuilder result = new StringBuilder();

        // Truncate if too long
        if (output != null && !output.isEmpty()) {
            if (output.length() > MAX_OUTPUT_LENGTH) {
                result.append(output, 0, MAX_OUTPUT_LENGTH);
                result.append("\n\n[Output truncated at ").append(MAX_OUTPUT_LENGTH).append(" characters]");
            } else {
                result.append(output);
            }
        }

        // Add exit code
        if (result.length() > 0 && !result.toString().endsWith("\n")) {
            result.append("\n");
        }
        result.append("[Exit code: ").append(exitCode).append("]");

        return result.toString();
    }
}
