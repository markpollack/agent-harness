# Spring AI Agent Harness

A foundation library implementing agentic loop patterns for building AI agents with Spring AI.

## Overview

Agent Harness provides composable, reusable patterns for building AI agents, with focus on SDLC/coding agents. It offers **two approaches** to agent loop control, each suited to different use cases.

## Quick Start

### Simple Agent (Recommended)

For most use cases, use the **advisor-based approach** with `AgentLoopAdvisor`:

```java
// Create advisor with loop control features
var advisor = AgentLoopAdvisor.builder()
    .toolCallingManager(toolCallingManager)
    .maxTurns(20)
    .timeout(Duration.ofMinutes(5))
    .listener(myListener)
    .build();

// Build ChatClient with advisors
var chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        new MessageChatMemoryAdvisor(memory),  // Optional: session persistence
        advisor                                 // Loop control
    )
    .defaultTools(myTools)
    .build();

// Run agent
ChatResponse response = chatClient.prompt()
    .user("Fix the failing tests")
    .call()
    .chatResponse();
```

### MiniAgent Example

A complete ~100 line agent is provided in `harness-examples`:

```java
var agent = new MiniAgent(config, chatModel);
MiniAgentResult result = agent.run("Implement the login feature");

if (result.isSuccess()) {
    System.out.println(result.output());
}
```

## Two Approaches to Loop Control

Agent Harness provides two approaches to agent loop control. **Choose based on your use case.**

### Approach 1: AgentLoopAdvisor (Recommended)

**Leverages Spring AI's recursive advisor infrastructure.**

```
┌─────────────────────────────────────────────────────────────────┐
│  AgentLoopAdvisor (extends ToolCallAdvisor)                     │
│                                                                 │
│  The loop lives INSIDE Spring AI's recursive advisor.           │
│  AgentLoopAdvisor adds control features via hooks:              │
│  - doInitializeLoop() → Reset state, start timer                │
│  - doBeforeCall()     → Check termination conditions            │
│  - doAfterCall()      → Track metrics, evaluate jury            │
└─────────────────────────────────────────────────────────────────┘
```

**Features:**
- Turn limiting
- Cost/token tracking
- Timeout enforcement
- Abort signal support
- Stuck detection
- Listener notifications
- Optional jury evaluation

**Use for:**
- CLI agents (interactive and autonomous)
- Simple benchmarks
- Most single-agent implementations
- ~95% of use cases

### Approach 2: Explicit Loop Patterns (Advanced)

**Full control with explicit `while` loops.**

```
┌─────────────────────────────────────────────────────────────────┐
│  TurnLimitedLoop / StateMachineLoop / EvaluatorOptimizerLoop   │
│                                                                 │
│  Explicit while(true) loop that calls ChatClient each turn.     │
│  Full control over every aspect of execution.                   │
└─────────────────────────────────────────────────────────────────┘
```

**Use for:**
- Multi-agent orchestration
- Graph-based workflows (conditional routing)
- Custom termination strategies beyond advisor hooks
- Research and experimentation
- ~5% of advanced use cases

## When to Use Each Approach

| Use Case | Recommended Approach |
|----------|---------------------|
| CLI agent (interactive) | `AgentLoopAdvisor` |
| CLI agent (autonomous) | `AgentLoopAdvisor` |
| Simple benchmark | `AgentLoopAdvisor` |
| Benchmark with jury | `AgentLoopAdvisor` (jury in `doAfterCall()`) |
| Cost-limited runs | `AgentLoopAdvisor` |
| Multi-agent coordination | Explicit Loop (`GraphCompositionStrategy`) |
| State machine agent | Explicit Loop (`StateMachineLoop`) |
| Iterative refinement | Explicit Loop (`EvaluatorOptimizerLoop`) |
| Custom orchestration | Explicit Loop |

## Module Structure

```
agent-harness/
├── harness-api/        # Core interfaces (AgentLoop, LoopState, TerminationStrategy)
├── harness-patterns/   # Loop implementations + advisors
│   ├── advisor/        # AgentLoopAdvisor (recommended)
│   ├── turnlimited/    # TurnLimitedLoop (explicit)
│   ├── statemachine/   # StateMachineLoop (explicit)
│   ├── evaluator/      # EvaluatorOptimizerLoop (explicit)
│   └── graph/          # GraphCompositionStrategy (multi-node)
├── harness-tools/      # Agent tools (Bash, Read, Write, Edit, Glob, Grep)
└── harness-examples/   # MiniAgent example
```

## AgentLoopAdvisor API

### Builder Configuration

```java
var advisor = AgentLoopAdvisor.builder()
    .toolCallingManager(manager)      // Required
    .maxTurns(20)                      // Default: 20
    .timeout(Duration.ofMinutes(5))   // Default: 10 min
    .costLimit(5.0)                   // Default: $5.00
    .stuckThreshold(3)                // Default: 3 same outputs
    .jury(myJury, 5)                  // Optional: evaluate every N turns
    .listener(myListener)             // Optional: event notifications
    .build();
```

### Listener Interface

```java
public interface AgentLoopListener {
    default void onLoopStarted(String runId, String userMessage) {}
    default void onTurnStarted(String runId, int turn) {}
    default void onTurnCompleted(String runId, int turn, TerminationReason reason) {}
    default void onLoopCompleted(String runId, LoopState state, TerminationReason reason) {}
    default void onLoopFailed(String runId, LoopState state, Throwable error) {}
}
```

### Termination Reasons

| Reason | Description |
|--------|-------------|
| `COMPLETED` | Agent finished normally (no more tool calls) |
| `MAX_TURNS_REACHED` | Hit maxTurns limit |
| `TIMEOUT` | Exceeded timeout duration |
| `COST_LIMIT_EXCEEDED` | Exceeded cost limit |
| `STUCK_DETECTED` | Same output N times in a row |
| `JURY_PASSED` | Jury evaluation passed |
| `ABORTED` | External abort signal received |

## Explicit Loop Patterns

For advanced use cases, explicit loop patterns provide full control:

### TurnLimitedLoop

```java
var loop = TurnLimitedLoop.builder()
    .config(TurnLimitedConfig.builder()
        .maxTurns(50)
        .timeout(Duration.ofMinutes(30))
        .jury(myJury)
        .evaluateEveryNTurns(5)
        .build())
    .listener(myListener)
    .build();

TurnLimitedResult result = loop.execute(userMessage, chatClient, tools);
```

### StateMachineLoop

For agents with explicit state transitions:

```java
var loop = StateMachineLoop.builder()
    .config(StateMachineConfig.builder()
        .initialState(AgentState.PLANNING)
        .addTransition(AgentState.PLANNING, AgentState.EXECUTING)
        .addTransition(AgentState.EXECUTING, AgentState.REVIEWING)
        .terminalState(AgentState.COMPLETE)
        .build())
    .build();
```

### GraphCompositionStrategy

For multi-node workflows:

```java
var graph = GraphCompositionStrategy.builder()
    .addNode("plan", planningAgent)
    .addNode("execute", executionAgent)
    .addNode("review", reviewAgent)
    .addEdge("plan", "execute")
    .addEdge("execute", "review")
    .addConditionalEdge("review", result ->
        result.needsRevision() ? "execute" : "END")
    .entryPoint("plan")
    .build();
```

## Session Memory

Session persistence is handled separately via Spring AI's `MessageChatMemoryAdvisor`:

```java
// With session (CLI interactive mode)
var chatClient = ChatClient.builder(model)
    .defaultAdvisors(
        new MessageChatMemoryAdvisor(new InMemoryChatMemory()),
        agentLoopAdvisor
    )
    .build();

// Without session (autonomous/benchmark mode)
var chatClient = ChatClient.builder(model)
    .defaultAdvisors(agentLoopAdvisor)  // No memory advisor
    .build();
```

## Build

```bash
# Build all modules
mvn clean compile

# Run tests
mvn test

# Install locally
mvn install
```

## Dependencies

- Spring AI 2.0-SNAPSHOT
- spring-ai-agents-judge 0.1.0-SNAPSHOT (optional, for jury evaluation)
- Java 21+

## Related Projects

- [agent-harness-cli](https://github.com/springaicommunity/agent-harness-cli) - Terminal UI for agent-harness
- [spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils) - Claude Code-inspired tools
- [mini-swe-agent](https://github.com/SWE-agent/mini-swe-agent) - Python reference (74%+ SWE-bench)

## License

Apache License 2.0
