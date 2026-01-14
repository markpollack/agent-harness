# Bash Tool Implementation Design Document

> **Source Analysis**: `/home/mark/tuvium/claude-code-analysis` (deobfuscated Claude Code CLI)
> **Target Project**: Tuvium Agent Harnesses (Spring AI / Java 21)
> **Created**: 2025-12-20

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Claude Code Bash Tool Feature Analysis](#claude-code-bash-tool-feature-analysis)
3. [Implementation Options](#implementation-options)
   - [Option A: Full Feature Parity](#option-a-full-feature-parity)
   - [Option B: Minimal Viable + Security](#option-b-minimal-viable--security)
   - [Option C: Minimal Viable (No Security)](#option-c-minimal-viable-no-security)
4. [Sandbox Architecture](#sandbox-architecture)
5. [Security Layer Design](#security-layer-design)
6. [Integration Points](#integration-points)
7. [Configuration Options](#configuration-options)
8. [OSS MCP Bash Tools Comparison](#oss-mcp-bash-tools-comparison)
9. [Implementation Phases](#implementation-phases)
10. [Critical Files](#critical-files)

---

## Executive Summary

This document provides a comprehensive design for implementing a Bash Tool for the Tuvium Agent Harnesses project, based on analysis of Claude Code's production implementation (deobfuscated from `cli.readable.js`).

**Key Findings from Claude Code Analysis:**

| Aspect | Claude Code Implementation |
|--------|---------------------------|
| **Input Parameters** | 5 parameters (command, timeout, description, run_in_background, dangerouslyDisableSandbox) |
| **Output Fields** | 10+ fields (stdout, stderr, summary, rawOutputPath, interrupted, isImage, backgroundTaskId, etc.) |
| **Security Layers** | 4-level permission system (allow/ask/deny/passthrough) + command injection detection |
| **Sandbox Options** | Bubblewrap (Linux) + ripgrep-based validation (cross-platform) |
| **Special Features** | Background execution, progress streaming, output truncation, image detection, MCP structured content |

---

## Claude Code Bash Tool Feature Analysis

### Complete Input Schema

```typescript
interface BashInput {
  command: string;              // Required - shell command to execute
  timeout?: number;             // Max 600,000ms (10 min), default 120,000ms
  description?: string;         // 5-10 word description (for logging/display)
  run_in_background?: boolean;  // Return immediately with task ID
  dangerouslyDisableSandbox?: boolean;  // Override sandbox (requires permission)
}
```

### Complete Output Schema

```typescript
interface BashOutput {
  stdout: string;                    // Standard output
  stderr: string;                    // Standard error
  exitCode: number;                  // Process exit code
  summary?: string;                  // Summary when output truncated
  rawOutputPath?: string;            // Path to full output file
  interrupted: boolean;              // Command was aborted
  isImage: boolean;                  // Stdout contains base64 image
  backgroundTaskId?: string;         // ID for background task tracking
  returnCodeInterpretation?: string; // Semantic meaning (e.g., "SIGKILL")
  structuredContent?: object[];      // MCP structured content blocks
}
```

### Security System Components

#### 1. Permission Behaviors
| Behavior | Description |
|----------|-------------|
| `allow` | Auto-approved without user interaction |
| `ask` | Requires user confirmation |
| `deny` | Blocked completely |
| `passthrough` | Deferred to underlying system |

#### 2. Command Classification

**Read-Only Commands (Auto-Allow):**
```
ls, cat, head, tail, wc, stat, file, tree, du, df, pwd, whoami,
hostname, uname, date, env, echo, which, whereis, man, less, more
```

**Search Commands (Auto-Allow):**
```
find, grep, rg, ag, ack, locate, fd, diff, sort, uniq
```

**Safe Development Commands (Allow in Sandbox):**
```
npm, yarn, pnpm, pip, cargo, go, make, mvn, gradle,
node, python, java, git, docker, jest, pytest
```

#### 3. Dangerous Pattern Detection
```regex
rm\s+(-[rf]+\s+)*(/|~|\$HOME)     # rm -rf /
:(\\s*)\\(\\s*\\).*\\|.*&          # Fork bomb
curl.*\\|.*bash                     # Curl pipe to bash
mkfs\\.                             # Format filesystem
dd\\s+if=.*of=/dev/                 # Direct disk write
```

#### 4. Command Injection Detection
```regex
\$\(                    # Command substitution
`[^`]+`                 # Backtick substitution
\|\s*bash               # Pipe to bash
&&\s*rm\s+-rf           # Chain to rm -rf
```

### Advanced Features

#### Background Execution
- Returns immediately with `backgroundTaskId`
- Output streams to buffer, retrievable via separate tool
- Supports task status queries: RUNNING, COMPLETED, FAILED, CANCELLED

#### Progress Reporting
- Real-time stdout/stderr streaming via async generator
- Elapsed time tracking
- Line count updates
- Offer to background after 2 seconds of execution

#### Output Processing
- **Truncation**: 30,000 character limit
- **Raw storage**: Full output saved to file when truncated
- **Image detection**: Base64 image content flagged
- **Exit code interpretation**: Maps codes to semantic meanings

---

## Implementation Options

### Option A: Full Feature Parity

**Goal**: Match Claude Code's complete bash tool feature set.

**Scope**: ~25 files, 2,500+ lines of code

#### Module Structure
```
harness-tools/
├── pom.xml
└── src/main/java/io/tuvium/harness/tools/
    ├── bash/
    │   ├── BashTool.java                    # Main @Tool entry point
    │   ├── BashToolConfig.java              # Configuration record
    │   ├── BashToolResult.java              # Full output schema
    │   ├── BashCommandParser.java           # Multi-command parsing
    │   └── security/
    │       ├── PermissionBehavior.java      # Enum: allow/ask/deny/passthrough
    │       ├── PermissionDecision.java      # Decision record with reason
    │       ├── PermissionContext.java       # User/project context
    │       ├── PermissionChecker.java       # Interface
    │       ├── DefaultPermissionChecker.java
    │       ├── CommandClassifier.java       # Read-only/safe detection
    │       └── InjectionDetector.java       # Command injection checks
    ├── sandbox/
    │   ├── SandboxExecutor.java             # Core interface
    │   ├── SandboxConfig.java               # Configuration
    │   ├── SandboxResult.java               # Execution result
    │   ├── LocalExecutor.java               # No sandbox (ProcessBuilder)
    │   ├── BubblewrapExecutor.java          # Linux namespace isolation
    │   ├── DockerExecutor.java              # Container isolation
    │   └── SandboxExecutorFactory.java      # Platform-aware factory
    ├── background/
    │   ├── BackgroundTask.java              # Task record
    │   ├── BackgroundTaskManager.java       # Lifecycle management
    │   └── BackgroundTaskResult.java        # Output retrieval
    └── autoconfigure/
        ├── BashToolProperties.java          # Spring Boot properties
        └── BashToolAutoConfiguration.java   # Auto-config
```

#### Key Classes

**BashTool.java** (Main Entry Point)
```java
@Component
public class BashTool {

    @Tool(name = "bash", description = "Execute bash command...")
    public BashToolResult execute(
        @ToolParam(required = true) String command,
        @ToolParam(required = false) Integer timeout,
        @ToolParam(required = false) String description,
        @ToolParam(required = false) Boolean runInBackground,
        @ToolParam(required = false) Boolean dangerouslyDisableSandbox
    ) {
        // 1. Validate inputs
        // 2. Check command injection
        // 3. Check permissions
        // 4. Select executor (sandbox/local)
        // 5. Execute (foreground or background)
        // 6. Process output (truncate, detect images)
        // 7. Return result with observability
    }

    @Tool(name = "bash_task_output")
    public BashToolResult getTaskOutput(@ToolParam String taskId) {
        // Retrieve background task output
    }
}
```

**CommandClassifier.java** (Security)
```java
public class CommandClassifier {

    public boolean isReadOnly(String command);      // ls, cat, grep, etc.
    public boolean isSafeCommand(String command);   // npm, git, make, etc.
    public boolean isDangerous(String command);     // rm -rf /, fork bomb
    public boolean hasCommandInjection(String command);

    public CommandClassification classify(String command);

    public enum CommandClassification {
        READ_ONLY,    // Auto-allow
        SAFE,         // Allow in sandbox
        UNKNOWN,      // Check permissions
        SUSPICIOUS,   // Likely deny
        DANGEROUS     // Always deny
    }
}
```

**SandboxExecutor.java** (Interface)
```java
public interface SandboxExecutor {

    SandboxResult execute(
        String command,
        Duration timeout,
        Path workingDirectory,
        Map<String, String> environmentVariables
    );

    SandboxResult execute(
        String command,
        Duration timeout,
        Path workingDirectory,
        Map<String, String> environmentVariables,
        ProgressListener listener  // For streaming output
    );

    String type();  // "local", "bubblewrap", "docker"
    SandboxExecutor withoutSandbox();
    void cleanup();

    interface ProgressListener {
        void onStdout(String line);
        void onStderr(String line);
        void onProgress(double progress, String message);
    }
}
```

---

### Option B: Minimal Viable + Security

**Goal**: Core execution with strong security, defer advanced features.

**Scope**: ~12 files, 800+ lines of code

**Included:**
- Command execution with timeout
- Permission system (allow/deny)
- Command classification (read-only/safe/dangerous)
- Command injection detection
- Sandbox execution (Bubblewrap + Docker factory)
- Basic output processing

**Deferred:**
- Background execution
- Progress streaming
- Image detection
- MCP structured content

#### Module Structure
```
harness-tools/
└── src/main/java/io/tuvium/harness/tools/
    ├── bash/
    │   ├── BashTool.java
    │   ├── BashToolConfig.java
    │   ├── BashToolResult.java
    │   └── security/
    │       ├── PermissionDecision.java
    │       ├── PermissionChecker.java
    │       └── CommandClassifier.java
    └── sandbox/
        ├── SandboxExecutor.java
        ├── SandboxResult.java
        ├── LocalExecutor.java
        ├── BubblewrapExecutor.java
        ├── DockerExecutor.java
        └── SandboxExecutorFactory.java
```

#### Simplified BashTool
```java
@Component
public class BashTool {

    @Tool(name = "bash")
    public BashToolResult execute(
        @ToolParam(required = true) String command,
        @ToolParam(required = false) Integer timeout
    ) {
        // 1. Check dangerous patterns → deny
        // 2. Check command injection → deny
        // 3. Classify command (read-only/safe/unknown)
        // 4. Execute in sandbox
        // 5. Return stdout/stderr/exitCode
    }
}
```

---

### Option C: Minimal Viable (No Security)

**Goal**: Fastest path to working bash execution.

**Scope**: ~5 files, 200 lines of code

**WARNING**: Only for development/testing environments. No production use.

#### Module Structure
```
harness-tools/
└── src/main/java/io/tuvium/harness/tools/bash/
    ├── BashTool.java
    ├── BashToolResult.java
    └── ProcessExecutor.java
```

#### Minimal Implementation
```java
@Component
public class BashTool {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;

    @Tool(name = "bash", description = "Execute a bash command")
    public BashToolResult execute(
        @ToolParam(description = "Command to execute", required = true)
        String command,
        @ToolParam(description = "Timeout in ms", required = false)
        Integer timeout
    ) {
        int effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT_MS;

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(false);

            Process process = pb.start();
            boolean completed = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);

            if (!completed) {
                process.destroyForcibly();
                return BashToolResult.timeout();
            }

            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());

            return new BashToolResult(stdout, stderr, process.exitValue());

        } catch (Exception e) {
            return BashToolResult.error(e.getMessage());
        }
    }
}
```

---

## Sandbox Architecture

### Factory Selection Strategy

```java
public class SandboxExecutorFactory {

    public static SandboxExecutor create(BashToolConfig config) {
        return switch (config.sandboxType()) {
            case LOCAL -> new LocalExecutor();
            case BUBBLEWRAP -> new BubblewrapExecutor(config);
            case DOCKER -> new DockerExecutor(config);
        };
    }

    public static SandboxExecutor createBestAvailable(BashToolConfig config) {
        // 1. Check for bubblewrap on Linux
        if (isLinux() && isBubblewrapAvailable()) {
            return new BubblewrapExecutor(config);
        }

        // 2. Check for Docker
        if (isDockerAvailable()) {
            return new DockerExecutor(config);
        }

        // 3. Fall back to local (no sandbox)
        return new LocalExecutor();
    }

    private static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    private static boolean isBubblewrapAvailable() {
        // Check: which bwrap
    }

    private static boolean isDockerAvailable() {
        // Check: docker info
    }
}
```

### Bubblewrap Executor (Linux)

**Key Features:**
- Namespace isolation without root privileges
- Fast startup (~10ms overhead)
- Matches Claude Code's production sandbox

**Command Construction:**
```java
List<String> cmd = List.of(
    "bwrap",
    "--unshare-user-try",
    "--ro-bind", "/usr", "/usr",
    "--ro-bind", "/bin", "/bin",
    "--ro-bind", "/lib", "/lib",
    "--ro-bind", "/lib64", "/lib64",  // If exists
    "--ro-bind", "/etc", "/etc",
    "--tmpfs", "/tmp",
    "--proc", "/proc",
    "--dev", "/dev",
    "--new-session",
    "--bind", workDir, workDir,       // R/W working directory
    "--chdir", workDir,
    "--setenv", "PATH", "/usr/local/bin:/usr/bin:/bin",
    "bash", "-c", command
);
```

### Docker Executor (Cross-Platform)

**Key Features:**
- Strong isolation
- Works on Linux, macOS, Windows
- Heavier startup (~500ms)

**Container Lifecycle:**
```java
// 1. Start persistent container
docker run -d --name tuvium-sandbox-xxx \
    --memory 5g --cpus 1 \
    --network=none \
    -v /workspace:/workspace \
    ubuntu:22.04 sleep 2h

// 2. Execute commands in container
docker exec -w /workspace tuvium-sandbox-xxx \
    bash -lc "command here"

// 3. Cleanup on shutdown
docker rm -f tuvium-sandbox-xxx
```

### Comparison Matrix

| Feature | Local | Bubblewrap | Docker |
|---------|-------|------------|--------|
| **Isolation** | None | Namespace | Container |
| **Startup Time** | 0ms | ~10ms | ~500ms |
| **Platform** | All | Linux | All |
| **Root Required** | No | No | No (daemon) |
| **Network Isolation** | No | Yes | Yes |
| **File System Isolation** | No | Yes | Yes |
| **Resource Limits** | No | Limited | Full |

---

## Security Layer Design

### Permission Flow

```
Command Input
     │
     ▼
┌─────────────────────┐
│ Command Injection   │──deny──▶ REJECTED
│ Detection           │
└─────────────────────┘
     │ pass
     ▼
┌─────────────────────┐
│ Dangerous Pattern   │──deny──▶ REJECTED
│ Detection           │
└─────────────────────┘
     │ pass
     ▼
┌─────────────────────┐
│ Command             │
│ Classification      │
└─────────────────────┘
     │
     ├──READ_ONLY────▶ AUTO-ALLOW
     │
     ├──SAFE─────────▶ ALLOW (in sandbox)
     │
     ├──UNKNOWN──────▶ Check Permission Context
     │                      │
     │                      ├──allowlist──▶ ALLOW
     │                      ├──blocklist──▶ DENY
     │                      └──interactive──▶ ASK USER
     │
     └──DANGEROUS────▶ DENY
```

### Command Classification Rules

```java
public CommandClassification classify(String command) {
    String firstCmd = extractFirstCommand(command);

    // Check dangerous patterns first
    if (matchesDangerousPattern(command)) {
        return DANGEROUS;
    }

    // Check for injection
    if (hasInjectionPattern(command)) {
        return SUSPICIOUS;
    }

    // Classify by command
    if (READ_ONLY_COMMANDS.contains(firstCmd)) {
        return READ_ONLY;
    }

    if (SAFE_COMMANDS.contains(firstCmd)) {
        return SAFE;
    }

    return UNKNOWN;
}
```

---

## Integration Points

### Spring AI Tool Registration

```java
// BashTool is auto-discovered via @Component and @Tool
@Bean
public BashTool bashTool(
    BashToolConfig config,
    PermissionChecker permissionChecker,
    SandboxExecutor sandboxExecutor,
    ObservabilityProvider observability
) {
    return new BashTool(config, permissionChecker, sandboxExecutor, observability);
}

// Register with ChatClient
var tools = ToolCallbacks.from(applicationContext.getBean(BashTool.class));
```

### Loop Pattern Integration

```java
// Use with TurnLimitedLoop
var loop = TurnLimitedLoop.<String>builder()
    .summaryBuilder((state, reason, verdict, response) ->
        response.getResult().getOutput().getText())
    .observability(observability)
    .build();

var result = loop.execute(
    "Find all TODO comments in the codebase",
    TurnLimitedConfig.builder()
        .maxTurns(20)
        .timeout(Duration.ofMinutes(5))
        .build(),
    chatClient,
    tools  // Includes BashTool
).block();
```

### Observability Integration

```java
// Metrics
observability.metrics().timer("bash.duration").record(result.duration());
observability.metrics().counter("bash.executions").increment();

// Events
observability.events().record("bash.execute", Map.of(
    "command_length", command.length(),
    "executor", sandboxExecutor.type(),
    "exit_code", result.exitCode()
));

// Calls (tracing)
CallTracker.Call call = observability.calls().startCall("bash.execute");
call.setAttribute("command.hash", hashCommand(command));
call.setAttribute("sandbox.type", sandboxExecutor.type());
// ... execution ...
call.close();
```

---

## Configuration Options

### Spring Boot Properties

```yaml
tuvium:
  tools:
    bash:
      # Execution settings
      default-timeout: 120000        # 2 minutes
      max-timeout: 600000            # 10 minutes
      max-output-length: 30000       # Characters before truncation

      # Sandbox settings
      sandbox-type: BUBBLEWRAP       # LOCAL, BUBBLEWRAP, DOCKER
      allow-disable-sandbox: false   # Honor dangerouslyDisableSandbox param
      docker-image: ubuntu:22.04

      # Security settings
      blocked-commands:
        - "rm -rf /"
        - ":(){ :|:& };:"
      blocked-patterns:
        - "curl.*\\|.*bash"
        - "wget.*\\|.*sh"

      # Paths
      working-directory: /workspace
      allowed-paths:
        - /home/user/projects
      blocked-paths:
        - /etc/passwd
        - /etc/shadow

      # Background execution
      max-background-tasks: 10
      task-retention-hours: 1
```

### Programmatic Configuration

```java
BashToolConfig config = BashToolConfig.builder()
    .workingDirectory(Path.of("/workspace"))
    .sandboxType(SandboxType.BUBBLEWRAP)
    .allowDisableSandbox(false)
    .defaultTimeout(Duration.ofMinutes(2))
    .maxTimeout(Duration.ofMinutes(10))
    .maxOutputLength(30_000)
    .blockedCommands(Set.of("rm -rf /"))
    .build();
```

---

## OSS CLI Bash Tools Comparison

### Implementation Overview

| Aspect | Claude Code | OpenAI Codex CLI | Gemini CLI |
|--------|-------------|------------------|------------|
| **Language** | TypeScript (bundled) | Rust | TypeScript |
| **Tool Name** | `Bash` | `shell` | `run_shell_command` |
| **Architecture** | Monolithic CLI | MCP Server + Patched Bash | Modular CLI |
| **Source** | `/home/mark/tuvium/claude-code-analysis` | `/home/mark/research/supporting_repos/openai-codex-cli` | `/home/mark/research/supporting_repos/gemini-cli` |

### Input Parameters Comparison

| Parameter | Claude Code | Codex CLI | Gemini CLI |
|-----------|-------------|-----------|------------|
| **command** | ✅ Required | ✅ Required | ✅ Required |
| **timeout** | ✅ 600s max, 120s default | ✅ 10s default | ✅ 5min inactivity |
| **description** | ✅ 5-10 words | ❌ | ✅ Up to 3 sentences |
| **working_dir** | ❌ (uses cwd) | ✅ Required (workdir) | ✅ Optional (dir_path) |
| **run_in_background** | ✅ | ❌ | ❌ (but captures bg PIDs) |
| **disable_sandbox** | ✅ dangerouslyDisableSandbox | ❌ (escalation instead) | ❌ |
| **login_shell** | ❌ | ✅ Optional (login flag) | ❌ |

### Output Schema Comparison

| Field | Claude Code | Codex CLI | Gemini CLI |
|-------|-------------|-----------|------------|
| **stdout** | ✅ | ✅ (combined output) | ✅ |
| **stderr** | ✅ Separate | ❌ Combined with stdout | ✅ Combined |
| **exit_code** | ✅ | ✅ | ✅ |
| **duration** | ✅ | ✅ | ❌ |
| **timed_out** | ✅ (interrupted) | ✅ | ✅ |
| **background_task_id** | ✅ | ❌ | ❌ |
| **background_pids** | ❌ | ❌ | ✅ |
| **raw_output_path** | ✅ | ❌ | ❌ |
| **is_image** | ✅ | ❌ | ❌ (binary detection) |
| **signal** | ✅ returnCodeInterpretation | ❌ | ✅ |
| **process_group_id** | ❌ | ❌ | ✅ (PGID) |

### Security & Sandboxing Comparison

| Feature | Claude Code | Codex CLI | Gemini CLI |
|---------|-------------|-----------|------------|
| **Sandbox Required** | Optional (configurable) | **Mandatory** | Optional |
| **Linux Sandbox** | Bubblewrap | Landlock + seccomp | Docker/Podman |
| **macOS Sandbox** | ❌ | Seatbelt (sandbox-exec) | Seatbelt |
| **Windows Sandbox** | ❌ | AppContainer (experimental) | ❌ |
| **Execve Interception** | ❌ | ✅ Patched bash | ❌ |
| **Permission Levels** | allow/ask/deny/passthrough | allow/prompt/forbidden | ALLOW/DENY/ASK_USER |
| **Policy Language** | JSON rules | Starlark (.rules files) | JSON config |
| **Command Classification** | ✅ Read-only/Safe/Dangerous | ❌ (policy-based) | ✅ Allowlist/Blocklist |
| **Injection Detection** | ✅ Regex patterns | ❌ (execve prevents) | ✅ Tree-sitter parsing |
| **Escalation Support** | ❌ | ✅ Run/Escalate/Deny | ❌ |

### Advanced Features Comparison

| Feature | Claude Code | Codex CLI | Gemini CLI |
|---------|-------------|-----------|------------|
| **Background Execution** | ✅ Full support | ❌ Synchronous only | Partial (captures PIDs) |
| **Progress Streaming** | ✅ Async generator | ✅ JSON event stream | ✅ 1s interval |
| **Output Truncation** | ✅ 30KB limit | ❌ | ✅ 16MB limit |
| **PTY Support** | ❌ | ❌ | ✅ xterm.js |
| **Interactive Commands** | ❌ | ❌ | ✅ vim, git rebase -i |
| **Binary Detection** | ✅ Base64 image | ❌ | ✅ First 4KB sniff |
| **Color Output** | ❌ | ❌ | ✅ ANSI preservation |
| **Environment Sanitization** | ✅ | ✅ | ✅ (CI-aware) |
| **Process Group Mgmt** | ❌ | ❌ | ✅ PGID termination |

### Timeout Strategies

| Aspect | Claude Code | Codex CLI | Gemini CLI |
|--------|-------------|-----------|------------|
| **Type** | Hard timeout | Hard timeout | Inactivity timeout |
| **Default** | 120 seconds | 10 seconds | 5 minutes |
| **Maximum** | 600 seconds (10 min) | Configurable | Configurable |
| **Pause During Prompts** | ❌ | ✅ (stopwatch) | ❌ |
| **Timeout Exit Code** | Custom | 124 (Unix standard) | Abort signal |

### Platform Support

| Platform | Claude Code | Codex CLI | Gemini CLI |
|----------|-------------|-----------|------------|
| **Linux** | ✅ Full | ✅ Full | ✅ Full |
| **macOS** | ✅ Limited sandbox | ✅ Full (Seatbelt) | ✅ Full |
| **Windows** | ✅ Git Bash | ⚠️ Experimental | ✅ PowerShell |

### Key File Locations

**OpenAI Codex CLI:**
| Component | Path |
|-----------|------|
| MCP Server | `codex-rs/exec-server/src/posix/mcp.rs` |
| Escalation Server | `codex-rs/exec-server/src/posix/escalate_server.rs` |
| Policy Engine | `codex-rs/exec-server/src/posix/mcp_escalation_policy.rs` |
| Bash Patch | `shell-tool-mcp/patches/bash-exec-wrapper.patch` |
| Documentation | `docs/sandbox.md`, `docs/exec.md` |

**Gemini CLI:**
| Component | Path |
|-----------|------|
| Tool Definition | `packages/core/src/tools/shell.ts` |
| Execution Service | `packages/core/src/services/shellExecutionService.ts` |
| Permissions | `packages/core/src/utils/shell-permissions.ts` |
| Policy Engine | `packages/core/src/policy/policy-engine.ts` |
| Sandbox Script | `scripts/sandbox_command.js` |

---

## Common Themes & Flexible Architecture

### Cross-Implementation Design Patterns

Based on deep analysis of Claude Code, OpenAI Codex CLI, and Gemini CLI, these common patterns emerge:

#### 1. Multi-Stage Execution Pipeline

All three implementations use a **layered pipeline** with distinct concerns:

```
┌─────────────────────────────────────────┐
│  1. Input Validation & Normalization    │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│  2. Policy/Permission Check             │
│     • Policy rules (highest priority)   │
│     • Heuristics fallback               │
│     • Mode check (interactive/auto)     │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│  3. Confirmation (if required)          │
│     • User approval                     │
│     • Timeout pause during prompt       │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│  4. Sandbox Selection & Setup           │
│     • Platform detection                │
│     • Sandbox type selection            │
│     • Environment preparation           │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│  5. Process Execution                   │
│     • Spawn with timeout race           │
│     • Stream output capture             │
│     • Signal handling                   │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│  6. Post-Execution Processing           │
│     • Output aggregation                │
│     • Sandbox denial detection          │
│     • Result finalization               │
└────────────────┬────────────────────────┘
                 ↓
┌─────────────────────────────────────────┐
│  7. Cleanup & Policy Update             │
│     • Process group termination         │
│     • Allowlist amendment (if approved) │
└─────────────────────────────────────────┘
```

#### 2. Three-Tier Permission Model

All implementations use a similar permission hierarchy:

| Tier | Codex CLI | Gemini CLI | Claude Code |
|------|-----------|------------|-------------|
| **Policy Layer** | Starlark `.rules` files | JSON allowlist/blocklist | JSON rules |
| **Heuristics Layer** | Command prefix matching | Root command extraction | Read-only/safe detection |
| **Mode Layer** | Never/OnFailure/OnRequest | Interactive/Non-interactive/YOLO | Sandbox on/off |

#### 3. Platform-Aware Sandbox Strategy

All use a **strategy pattern** for platform-specific sandboxing:

```java
// Recommended Java implementation
public interface SandboxStrategy {
    boolean isAvailable();
    SandboxResult execute(CommandSpec spec, SandboxConfig config);
    void cleanup();
}

public class SandboxStrategyFactory {
    private static final List<SandboxStrategy> STRATEGIES = List.of(
        new BubblewrapStrategy(),    // Linux preferred
        new LandlockStrategy(),      // Linux fallback
        new SeatbeltStrategy(),      // macOS
        new DockerStrategy(),        // Cross-platform
        new LocalStrategy()          // No sandbox (fallback)
    );

    public static SandboxStrategy selectBest(SandboxPolicy policy) {
        return STRATEGIES.stream()
            .filter(SandboxStrategy::isAvailable)
            .filter(s -> s.supportsPolicy(policy))
            .findFirst()
            .orElse(new LocalStrategy());
    }
}
```

#### 4. Timeout as Async Race

All implementations treat timeout as a **race condition**, not a blocking sleep:

```java
// Recommended pattern
CompletableFuture<ProcessResult> execution = executeAsync(command);
CompletableFuture<Void> timeout = CompletableFuture.runAsync(() -> {
    Thread.sleep(timeoutMs);
}, scheduler);

CompletableFuture.anyOf(execution, timeout)
    .thenAccept(result -> {
        if (result == null) {
            // Timeout won - kill process group
            killProcessGroup(process.pid());
        }
    });
```

#### 5. Process Group Management

All handle **grandchild processes** correctly:

| Aspect | Pattern |
|--------|---------|
| **Spawn** | Detached process group (`setsid` on Unix) |
| **Capture** | Track PGID for group operations |
| **Timeout Kill** | `kill(-pgid, SIGKILL)` - negative PID kills group |
| **Background PIDs** | Capture with `pgrep -g 0` after command |

#### 6. Output Streaming + Aggregation

All provide **both** real-time streaming and final aggregation:

```java
public interface OutputHandler {
    // Streaming (real-time)
    void onStdoutChunk(String chunk);
    void onStderrChunk(String chunk);
    void onProgress(Duration elapsed, int lineCount);

    // Aggregation (final)
    String getAggregatedStdout();
    String getAggregatedStderr();
    boolean wasTruncated();
    Path getRawOutputPath();  // If truncated
}
```

#### 7. Environment Variable Sanitization

All filter environment variables before passing to child:

| Strategy | Description |
|----------|-------------|
| **Inherit Core** | PATH, HOME, SHELL, LANG, USER, TMPDIR |
| **Block Secrets** | *_KEY, *_SECRET, *_TOKEN, *_PASSWORD |
| **CI Awareness** | Extra filtering in GitHub Actions/CI |
| **Tool Markers** | Add GEMINI_CLI=1 or similar detection flag |

---

### Recommended Pluggable Architecture for Tuvium

Based on the common patterns, here's a flexible architecture using Java/Spring:

#### Core Interfaces

```java
// 1. Permission System (Pluggable)
public interface PermissionChecker {
    PermissionDecision check(CommandContext context);
}

public record PermissionDecision(
    Decision decision,      // ALLOW, DENY, ASK, ESCALATE
    String reason,
    Optional<PolicyAmendment> proposedAmendment
) {}

// 2. Command Classifier (Pluggable)
public interface CommandClassifier {
    CommandClassification classify(String command);
    List<String> extractRootCommands(String command);
    boolean hasInjectionPattern(String command);
}

// 3. Sandbox Executor (Pluggable - Strategy Pattern)
public interface SandboxExecutor {
    boolean isAvailable();
    String type();
    SandboxResult execute(ExecutionSpec spec);
    Mono<SandboxResult> executeAsync(ExecutionSpec spec, OutputHandler handler);
    void cleanup();
}

// 4. Output Handler (Pluggable)
public interface OutputHandler {
    void onOutput(OutputEvent event);
    OutputResult finalize();
}

// 5. Policy Engine (Pluggable)
public interface PolicyEngine {
    void loadRules(Path rulesDirectory);
    PolicyDecision evaluate(String command, PolicyContext context);
    void amendPolicy(PolicyAmendment amendment);
}
```

#### Composition Root

```java
@Configuration
public class BashToolConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PermissionChecker permissionChecker(
            CommandClassifier classifier,
            PolicyEngine policyEngine) {
        return new DefaultPermissionChecker(classifier, policyEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public SandboxExecutor sandboxExecutor(BashToolProperties props) {
        return SandboxExecutorFactory.createBestAvailable(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandClassifier commandClassifier() {
        return new DefaultCommandClassifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public PolicyEngine policyEngine(BashToolProperties props) {
        return new FilePolicyEngine(props.getRulesDirectory());
    }

    @Bean
    public BashTool bashTool(
            PermissionChecker permissionChecker,
            SandboxExecutor sandboxExecutor,
            CommandClassifier classifier,
            OutputHandler outputHandler,
            ObservabilityProvider observability) {
        return new BashTool(
            permissionChecker,
            sandboxExecutor,
            classifier,
            outputHandler,
            observability
        );
    }
}
```

#### Extension Points Summary

| Extension Point | Interface | Default Implementation | Custom Examples |
|----------------|-----------|----------------------|-----------------|
| **Permission Checking** | `PermissionChecker` | `DefaultPermissionChecker` | `LdapPermissionChecker`, `OpaPermissionChecker` |
| **Command Classification** | `CommandClassifier` | `DefaultCommandClassifier` | `TreeSitterClassifier`, `AstGrepClassifier` |
| **Sandbox Execution** | `SandboxExecutor` | `BubblewrapExecutor` | `DockerExecutor`, `E2BExecutor`, `GVisorExecutor` |
| **Output Handling** | `OutputHandler` | `BufferedOutputHandler` | `StreamingOutputHandler`, `S3OutputHandler` |
| **Policy Engine** | `PolicyEngine` | `FilePolicyEngine` | `DatabasePolicyEngine`, `RemotePolicyEngine` |

---

### Configuration Layering (Following Codex Pattern)

```yaml
# Layer 1: System defaults (lowest priority)
# Built into the application

# Layer 2: Global config (~/.tuvium/config.yaml)
tuvium:
  tools:
    bash:
      sandbox-type: AUTO
      default-timeout: 120000

# Layer 3: Project config (.tuvium/config.yaml in project root)
tuvium:
  tools:
    bash:
      allowed-commands:
        - npm
        - yarn
        - mvn
      blocked-patterns:
        - "rm -rf /"

# Layer 4: Environment variables (higher priority)
TUVIUM_BASH_SANDBOX_TYPE=DOCKER
TUVIUM_BASH_TIMEOUT=300000

# Layer 5: Runtime parameters (highest priority)
bashTool.execute(command, BashToolConfig.builder()
    .timeout(Duration.ofMinutes(5))
    .sandboxType(SandboxType.BUBBLEWRAP)
    .build());
```

---

### Key Architectural Insights

| Insight | Implication for Tuvium |
|---------|------------------------|
| **Layered Validation** | Never rely on just one security check |
| **Explicit Sandbox Selection** | Detect platform early, not at execution time |
| **Streaming + Aggregation** | Support both real-time and batch output |
| **Timeout as Race** | Use `CompletableFuture.anyOf()` pattern |
| **Process Group Cleanup** | Always use `-pgid` for kill operations |
| **Exit Code Mapping** | Use 128+signal for signal-based exits |
| **Keyword Heuristics** | Detect sandbox denial via output keywords |
| **Environment Whitelist** | Filter env vars, don't pass blindly |
| **Audit Trail** | Log all permission decisions with justification |
| **Policy Amendment** | Allow runtime policy updates from user approvals |

---

## Implementation Phases

### Phase 1: Core Infrastructure
**Files:**
- `harness-tools/pom.xml`
- `bash/BashToolConfig.java`
- `bash/BashToolResult.java`
- `sandbox/SandboxExecutor.java`
- `sandbox/SandboxResult.java`
- `sandbox/LocalExecutor.java`

### Phase 2: Security Layer
**Files:**
- `bash/security/PermissionDecision.java`
- `bash/security/PermissionChecker.java`
- `bash/security/CommandClassifier.java`

### Phase 3: Sandbox Executors
**Files:**
- `sandbox/BubblewrapExecutor.java`
- `sandbox/DockerExecutor.java`
- `sandbox/SandboxExecutorFactory.java`

### Phase 4: Background Execution (Option A only)
**Files:**
- `background/BackgroundTask.java`
- `background/BackgroundTaskManager.java`

### Phase 5: Main Tool & Integration
**Files:**
- `bash/BashTool.java`
- `autoconfigure/BashToolProperties.java`
- `autoconfigure/BashToolAutoConfiguration.java`

### Phase 6: Testing
**Files:**
- `test/.../BashToolTest.java`
- `test/.../CommandClassifierTest.java`
- `test/.../BubblewrapExecutorTest.java`
- `test/.../DockerExecutorTest.java`

---

## Critical Files

### Existing Files to Reference

| File | Purpose |
|------|---------|
| `/home/mark/tuvium/claude-code-analysis/cli.readable.js:510807-511120` | Claude Code bash tool definition |
| `/home/mark/tuvium/claude-code-analysis/cli.readable.js:510574-510684` | Command execution (w87 function) |
| `/home/mark/tuvium/claude-code-analysis/cli.readable.js:433012-433178` | Permission checking (GC0 function) |
| `/home/mark/tuvium/claude-code-analysis/cli.readable.js:509567-509604` | Read-only detection (w59 function) |
| `/home/mark/tuvium/spring-ai-agent-harnesses/plans/SPRING-AI-INTEGRATION-PLAN.md` | Existing tool strategy |
| `/home/mark/tuvium/spring-ai-agent-harnesses/harness-patterns/src/.../TurnLimitedLoop.java` | Loop integration reference |
| `/home/mark/tuvium/spring-ai-agent-harnesses/harness-observability/src/.../ObservabilityProvider.java` | Observability integration |

### New Files to Create

| File | Lines (Est.) | Priority |
|------|--------------|----------|
| `harness-tools/pom.xml` | 80 | P0 |
| `bash/BashTool.java` | 200 | P0 |
| `bash/BashToolResult.java` | 100 | P0 |
| `bash/BashToolConfig.java` | 80 | P0 |
| `sandbox/SandboxExecutor.java` | 50 | P0 |
| `sandbox/SandboxResult.java` | 30 | P0 |
| `sandbox/LocalExecutor.java` | 100 | P0 |
| `sandbox/BubblewrapExecutor.java` | 150 | P1 |
| `sandbox/DockerExecutor.java` | 180 | P1 |
| `sandbox/SandboxExecutorFactory.java` | 60 | P1 |
| `bash/security/CommandClassifier.java` | 150 | P1 |
| `bash/security/PermissionChecker.java` | 30 | P1 |
| `bash/security/PermissionDecision.java` | 30 | P1 |
| `background/BackgroundTask.java` | 60 | P2 |
| `background/BackgroundTaskManager.java` | 150 | P2 |
| `autoconfigure/BashToolAutoConfiguration.java` | 60 | P2 |
| `autoconfigure/BashToolProperties.java` | 80 | P2 |

---

## Next Steps

1. **Choose implementation option** (A/B/C) based on timeline and requirements
2. **Research OSS MCP tools** to complete comparison matrix
3. **Create harness-tools module** with Maven configuration
4. **Implement Phase 1** (Core Infrastructure)
5. **Iterate** through remaining phases

---

## Appendix: Public Documentation vs. Reverse Engineering Analysis

### Summary

This section analyzes which features in this design document are **publicly documented** vs. discovered through **reverse engineering** (code analysis).

### Claude Code (Anthropic)

**Official Documentation Sources:**
- [Bash Tool Documentation](https://platform.claude.com/docs/en/agents-and-tools/tool-use/bash-tool)
- [Sandboxing Documentation](https://code.claude.com/docs/en/sandboxing)
- [Engineering Blog: Claude Code Sandboxing](https://www.anthropic.com/engineering/claude-code-sandboxing)

#### Input Parameters

| Parameter | Publicly Documented | Reverse Engineered |
|-----------|:------------------:|:-----------------:|
| `command` (string, required) | ✅ Yes | - |
| `restart` (boolean) | ✅ Yes | - |
| `timeout` (number, ms) | ❌ No | ✅ Found in code |
| `description` (string) | ❌ No | ✅ Found in code |
| `run_in_background` (boolean) | ❌ No | ✅ Found in code |
| `dangerouslyDisableSandbox` (boolean) | ⚠️ Mentioned in sandboxing docs | ✅ Full behavior in code |

#### Output Schema

| Field | Publicly Documented | Reverse Engineered |
|-------|:------------------:|:-----------------:|
| stdout | ✅ Yes ("stdout and stderr") | - |
| stderr | ✅ Yes (combined mention) | ✅ Separate field in code |
| exitCode | ❌ No | ✅ Found in code |
| summary | ❌ No | ✅ Found in code |
| rawOutputPath | ❌ No | ✅ Found in code |
| interrupted | ❌ No | ✅ Found in code |
| isImage | ❌ No | ✅ Found in code |
| backgroundTaskId | ❌ No | ✅ Found in code |
| returnCodeInterpretation | ❌ No | ✅ Found in code |
| structuredContent | ❌ No | ✅ Found in code |

#### Security Features

| Feature | Publicly Documented | Reverse Engineered |
|---------|:------------------:|:-----------------:|
| Bubblewrap sandbox (Linux) | ✅ Yes | - |
| Seatbelt sandbox (macOS) | ✅ Yes | - |
| Filesystem isolation | ✅ Yes | - |
| Network isolation via proxy | ✅ Yes | - |
| Auto-allow mode | ✅ Yes | - |
| 4-level permission (allow/ask/deny/passthrough) | ❌ No | ✅ Found in code |
| Read-only command detection | ⚠️ "echo, cat auto-approved" | ✅ Full list in code |
| Safe command classification | ❌ No | ✅ Found in code |
| Dangerous pattern detection (regex) | ❌ No | ✅ Found in code |
| Command injection detection | ❌ No | ✅ Found in code |
| UNC path protection (Windows) | ❌ No | ✅ Found in code |

#### Advanced Features

| Feature | Publicly Documented | Reverse Engineered |
|---------|:------------------:|:-----------------:|
| Background execution | ❌ No | ✅ Found in code |
| Progress streaming | ❌ No | ✅ Found in code |
| Output truncation (30K chars) | ⚠️ "may be truncated" | ✅ Exact limit in code |
| Image detection (base64) | ❌ No | ✅ Found in code |
| Timeout (600s max, 120s default) | ❌ No | ✅ Found in code |

---

### OpenAI Codex CLI

**Official Documentation Sources:**
- `/docs/sandbox.md` in repository
- `/docs/exec.md` in repository
- `/docs/execpolicy.md` in repository
- `/docs/config.md` in repository

#### Input Parameters

| Parameter | Publicly Documented | Implementation Detail |
|-----------|:------------------:|:--------------------:|
| `command` (string or array) | ✅ Yes | - |
| `workdir` (path, required) | ✅ Yes | - |
| `timeout_ms` (number) | ✅ Yes | - |
| `login` (boolean) | ✅ Yes | - |
| `justification` (string) | ✅ Yes | - |
| `sandbox_permissions` (object) | ⚠️ Mentioned | ✅ Structure in code |

#### Output Schema

| Field | Publicly Documented | Implementation Detail |
|-------|:------------------:|:--------------------:|
| exit_code | ✅ Yes | - |
| stdout (StreamOutput) | ⚠️ Mentioned | ✅ Wrapper structure in code |
| stderr (StreamOutput) | ⚠️ Mentioned | ✅ Wrapper structure in code |
| aggregated_output | ❌ No | ✅ Found in code |
| duration | ✅ Yes | - |
| timed_out | ✅ Yes | - |

#### Security Features

| Feature | Publicly Documented | Implementation Detail |
|---------|:------------------:|:--------------------:|
| Seatbelt (macOS) | ✅ Yes | - |
| Landlock + seccomp (Linux) | ✅ Yes | - |
| AppContainer (Windows) | ✅ Yes (experimental) | - |
| Starlark policy rules | ✅ Yes | - |
| Approval policies (untrusted/on-request/never) | ✅ Yes | - |
| Environment filtering patterns | ✅ Yes | - |
| Execve interception via patched bash | ❌ No | ✅ Found in code |
| BASH_EXEC_WRAPPER mechanism | ❌ No | ✅ Found in code |
| Escalation protocol (Run/Escalate/Deny) | ❌ No | ✅ Found in code |
| FD forwarding over Unix sockets | ❌ No | ✅ Found in code |

---

### Gemini CLI

**Official Documentation Sources:**
- `/docs/tools/shell.md` in repository
- `/docs/cli/sandbox.md` in repository

#### Input Parameters

| Parameter | Publicly Documented | Implementation Detail |
|-----------|:------------------:|:--------------------:|
| `command` (string) | ✅ Yes | - |
| `description` (string) | ✅ Yes | - |
| `dir_path` (string) | ✅ Yes | - |

#### Output Schema

| Field | Publicly Documented | Implementation Detail |
|-------|:------------------:|:--------------------:|
| Command | ✅ Yes | - |
| Directory | ✅ Yes | - |
| Stdout | ✅ Yes | - |
| Stderr | ✅ Yes | - |
| Error | ✅ Yes | - |
| Exit Code | ✅ Yes | - |
| Signal | ✅ Yes | - |
| Background PIDs | ✅ Yes | - |
| llmContent vs returnDisplay | ❌ No | ✅ Found in code |
| Process Group ID (PGID) | ❌ No | ✅ Found in code |

#### Security Features

| Feature | Publicly Documented | Implementation Detail |
|---------|:------------------:|:--------------------:|
| Command prefix allowlist | ✅ Yes | - |
| Command prefix blocklist | ✅ Yes | - |
| "NOT a security mechanism" disclaimer | ✅ Yes | - |
| Docker/Podman sandbox | ✅ Yes | - |
| Seatbelt (macOS) | ✅ Yes | - |
| Interactive shell (PTY) | ✅ Yes | - |
| GEMINI_CLI=1 env variable | ✅ Yes | - |
| Tree-sitter parsing for validation | ❌ No | ✅ Found in code |
| Binary output detection (4KB sniff) | ❌ No | ✅ Found in code |
| Inactivity timeout mechanism | ⚠️ Mentioned | ✅ Details in code |

---

### Reverse Engineering Uniqueness Ranking

Fields ranked 1-10 where **10 = Only discoverable through reverse engineering** and **1 = Common process management knowledge**.

#### Exclusion Criteria (Score 1-3: Common Knowledge)
These are standard process/shell concepts that any developer would expect:

| Field/Feature | Score | Rationale |
|--------------|:-----:|-----------|
| `stdout` | 1 | Universal process output |
| `stderr` | 1 | Universal process output |
| `exitCode` / `exit_code` | 1 | POSIX standard |
| `duration` | 2 | Common execution metric |
| `timeout` | 2 | Standard process control |
| `timed_out` / `interrupted` | 2 | Obvious timeout indicator |
| `workdir` / `cwd` | 1 | Standard shell concept |
| `command` | 1 | Required parameter |
| "dangerous mode" / YOLO | 3 | Common security bypass pattern |
| Environment variables | 1 | Standard process concept |
| Process group / PGID | 3 | Standard Unix concept |
| Signal handling | 2 | POSIX standard |

---

#### Truly Reverse-Engineered Discoveries (Score 7-10)

**Claude Code Unique Discoveries:**

| Field/Feature | Score | Why This is Unique |
|--------------|:-----:|-------------------|
| `isImage` | **10** | Detects base64-encoded images in stdout - completely undocumented, Claude-specific heuristic |
| `rawOutputPath` | **9** | File path where truncated output is saved - specific implementation detail |
| `returnCodeInterpretation` | **9** | Semantic string like "SIGKILL" mapped from exit codes - unique feature |
| `structuredContent` | **9** | MCP structured content blocks in output - undocumented protocol extension |
| `backgroundTaskId` | **8** | Specific ID format and retrieval mechanism for background tasks |
| `summary` | **8** | LLM-generated summary when output is truncated - AI-specific feature |
| `run_in_background` parameter | **7** | Exact parameter name and behavior undocumented |
| `description` parameter (5-10 words) | **7** | Specific constraint and purpose undocumented |
| 4-level permission: `passthrough` | **8** | The 4th level beyond allow/ask/deny is unique |
| Read-only command whitelist (exact list) | **8** | `ls, cat, head, tail, wc, stat, file, tree, du, df, pwd, whoami, hostname, uname, date, env, echo, which, whereis, man, less, more` |
| Safe command whitelist (exact list) | **8** | `npm, yarn, pnpm, pip, cargo, go, make, mvn, gradle, node, python, java, git, docker, jest, pytest` |
| Dangerous pattern regexes | **9** | `rm\s+(-[rf]+\s+)*(/\|~\|\$HOME)`, fork bomb pattern, `curl.*\|.*bash` |
| Command injection regexes | **9** | `\$\(`, backtick detection, pipe-to-bash patterns |
| UNC path protection | **10** | WebDAV attack mitigation via Windows UNC path blocking - very obscure |
| Output truncation limit: 30,000 chars | **7** | Exact limit only in code |
| Timeout: 600s max, 120s default | **6** | Specific values only in code |
| "Offer to background after 2 seconds" | **9** | UX behavior only discoverable by observation |

**OpenAI Codex CLI Unique Discoveries:**

| Field/Feature | Score | Why This is Unique |
|--------------|:-----:|-------------------|
| `BASH_EXEC_WRAPPER` env var | **10** | Patched bash mechanism to intercept execve - not in any docs |
| Execve interception via patched bash | **10** | The core sandboxing trick - requires reading bash patch |
| `aggregated_output` field | **8** | Combined stdout+stderr field undocumented |
| Escalation protocol (Run/Escalate/Deny) | **9** | Three-way decision for sandbox escape |
| FD forwarding via SCM_RIGHTS | **10** | Unix socket file descriptor passing - deep implementation |
| `CODEX_ESCALATE_SOCKET` env var | **10** | Internal IPC mechanism |
| Sandbox denial keyword detection | **9** | Post-execution heuristic: "operation not permitted", "seccomp", "landlock" |
| `StreamOutput` wrapper structure | **7** | `{ text, truncated_after_lines }` wrapper |
| Platform-specific bash binary selection | **8** | Checks `/etc/os-release` for Ubuntu/Debian version matching |
| MAX_EXEC_OUTPUT_DELTAS_PER_CALL: 10,000 | **8** | Streaming event limit |
| I/O drain timeout: 2000ms | **8** | Pipe close timeout after process exit |

**Gemini CLI Unique Discoveries:**

| Field/Feature | Score | Why This is Unique |
|--------------|:-----:|-------------------|
| `llmContent` vs `returnDisplay` | **9** | Internal distinction between LLM history and user display |
| Binary output sniffing (first 4KB) | **8** | Heuristic to detect binary streams |
| Tree-sitter bash parsing for validation | **8** | WebAssembly parser for command analysis |
| `BASH_SHOPT_GUARD` safety options | **9** | `shopt -u promptvars nullglob extglob nocaseglob dotglob` |
| Command wrapping for PID capture | **8** | `{ <cmd> }; __code=$?; pgrep -g 0 ><temp>; exit $__code` |
| Output update interval: 1000ms | **7** | Streaming throttle rate |
| Scrollback buffer: 300,000 lines | **7** | PTY terminal emulation limit |
| Memory formatting for binary progress | **7** | "X KB received" display logic |

---

### Summary: Top 10 Most Unique Reverse-Engineered Discoveries

| Rank | Feature | CLI | Score | Description |
|:----:|---------|-----|:-----:|-------------|
| 1 | `BASH_EXEC_WRAPPER` mechanism | Codex | 10 | Patched bash intercepts all execve syscalls |
| 2 | FD forwarding via SCM_RIGHTS | Codex | 10 | Unix socket passes stdin/stdout/stderr FDs |
| 3 | UNC path protection | Claude | 10 | Blocks Windows WebDAV attack vectors |
| 4 | `isImage` detection | Claude | 10 | Sniffs base64 image content in stdout |
| 5 | `CODEX_ESCALATE_SOCKET` | Codex | 10 | Internal escalation IPC channel |
| 6 | Command injection regexes | Claude | 9 | Specific patterns: `\$\(`, backticks, pipe-to-bash |
| 7 | Dangerous pattern regexes | Claude | 9 | Fork bomb, rm -rf /, curl\|bash patterns |
| 8 | `returnCodeInterpretation` | Claude | 9 | Maps exit codes to semantic strings |
| 9 | `structuredContent` | Claude | 9 | MCP protocol extension in output |
| 10 | `llmContent` vs `returnDisplay` | Gemini | 9 | Separate formats for LLM vs user |

---

### Implications for Tuvium Implementation

1. **Document Everything** - Unlike the source implementations, Tuvium should fully document all parameters, output fields, and security features

2. **API Contract Stability** - Reverse-engineered features may change without notice; design for flexibility

3. **Feature Parity Decisions** - Some features (like Claude's `isImage` detection) may be implementation-specific and not essential

4. **Security Transparency** - Document security mechanisms clearly rather than relying on obscurity

---

## Appendix: Exit Code Reference

| Code | Signal | Meaning |
|------|--------|---------|
| 0 | - | Success |
| 1 | - | General error |
| 2 | - | Misuse of shell command |
| 126 | - | Command cannot execute (permission) |
| 127 | - | Command not found |
| 128 | - | Invalid exit argument |
| 130 | SIGINT | Terminated by Ctrl+C |
| 137 | SIGKILL | Killed (OOM or timeout) |
| 139 | SIGSEGV | Segmentation fault |
| 143 | SIGTERM | Terminated |
