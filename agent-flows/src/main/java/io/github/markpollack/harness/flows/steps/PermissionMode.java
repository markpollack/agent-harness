/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.harness.flows.steps;

/**
 * Permission mode for {@link ClaudeStep} controlling how the Claude CLI handles
 * tool use permissions during its agentic loop.
 */
public enum PermissionMode {

    /**
     * Default permission handling — the CLI prompts or uses its configured defaults.
     */
    DEFAULT,

    /**
     * Automatically accept file edits (Bash, Write, Edit) without prompting.
     * Suitable for CI/CD and unattended automation contexts.
     * Maps to {@code --allowedTools Bash,Write,Edit} in the Claude CLI.
     */
    ACCEPT_EDITS,

    /**
     * Bypass all permission checks.
     * Maps to {@code --dangerously-skip-permissions} in the Claude CLI.
     * Use only in fully sandboxed/isolated environments.
     */
    BYPASS_PERMISSIONS
}
