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
package org.springaicommunity.agents.harness.agents.mini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tools available to the MiniAgent.
 * <p>
 * These methods are annotated with @Tool and will be converted to ToolCallbacks
 * via Spring AI's ToolCallbacks.from() utility.
 */
public class MiniAgentTools {

    private static final Logger log = LoggerFactory.getLogger(MiniAgentTools.class);

    private final Path workingDirectory;
    private final Duration timeout;

    public MiniAgentTools(Path workingDirectory, Duration timeout) {
        this.workingDirectory = workingDirectory;
        this.timeout = timeout;
    }

    /**
     * Execute a bash command and return the output.
     *
     * @param command The bash command to execute
     * @return The command output (stdout and stderr combined)
     */
    @Tool(description = "Execute a bash command in the working directory. Returns stdout and stderr.")
    public String bash(
            @ToolParam(description = "The bash command to execute") String command) {

        log.debug("Executing command: {}", command);

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

            // Format output similar to mini-swe-agent
            return formatOutput(output, exitCode);

        } catch (TimeoutException e) {
            log.warn("Command timed out after {}: {}", timeout, command);
            return "<timeout>Command timed out after " + timeout.toSeconds() + " seconds</timeout>";
        } catch (IOException | InterruptedException e) {
            log.error("Command execution failed: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "<error>" + e.getMessage() + "</error>";
        }
    }

    /**
     * Submit the final answer and complete the task.
     * <p>
     * Using returnDirect=true means the result is returned directly to the user
     * without going back to the model, effectively terminating the agent loop.
     *
     * @param answer The final answer to submit
     * @return The submitted answer
     */
    @Tool(description = "Submit your final answer when the task is complete. This ends the conversation.",
          returnDirect = true)
    public String submit(
            @ToolParam(description = "The final answer or result of the task") String answer) {

        log.info("Task submitted with answer: {}", truncate(answer, 100));
        return answer;
    }

    private String formatOutput(String output, int exitCode) {
        StringBuilder sb = new StringBuilder();
        if (output != null && !output.isEmpty()) {
            sb.append("<output>\n").append(output);
            if (!output.endsWith("\n")) {
                sb.append("\n");
            }
            sb.append("</output>\n");
        }
        sb.append("<returncode>").append(exitCode).append("</returncode>");
        return sb.toString();
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength) + "...";
    }
}
