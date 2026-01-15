# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring AI Agent Harnesses is a foundation library implementing all 8 agentic loop patterns discovered in academic research. It provides composable, reusable patterns for building AI agents using Spring AI, with focus on SDLC/coding agents like Claude CLI.

**Key Insight**: Different loop patterns = different judge configurations (Judge-centric architecture)

## Build Commands

```bash
# Build all modules
mvn clean compile

# Run unit tests
mvn test

# Run integration tests (requires ANTHROPIC_API_KEY env var)
mvn verify

# Run tests with coverage report
mvn clean test
# HTML reports at: */target/site/jacoco/index.html

# Build specific module
mvn compile -pl harness-patterns

# Run a single test class
mvn test -pl harness-patterns -Dtest=TurnLimitedLoopTest

# Run a single test method
mvn test -pl harness-patterns -Dtest=TurnLimitedLoopTest#testMaxTurnsTermination
```

## Code Coverage

JaCoCo is configured for all modules. After `mvn test`, HTML reports are generated:

```
harness-api/target/site/jacoco/index.html
harness-patterns/target/site/jacoco/index.html
harness-tools/target/site/jacoco/index.html
harness-examples/target/site/jacoco/index.html
```

**Coverage Targets:**

| Module | Target |
|--------|--------|
| harness-api | 80% |
| harness-patterns | 70% |
| harness-tools | 80% |
| harness-examples | 60% |

**Testing Standards:**
- Use AssertJ for assertions: `assertThat(result).isEqualTo(expected)`
- BDD-style naming: `methodShouldExpectedBehaviorWhenCondition()`
- AAA pattern: Arrange/Act/Assert
- Don't test auto-generated methods (records, getters)

## Architecture

### Module Structure

```
spring-ai-agent-harnesses/
├── harness-api/        # Core interfaces (AgentLoop, LoopState, LoopResult, TerminationStrategy)
├── harness-patterns/   # Loop implementations + composition strategies
├── harness-tools/      # Agent tools (Bash, Read, Write, Edit, Glob, Grep)
├── harness-examples/   # MiniAgent example (~100 lines)
└── plans/              # Design documents, research, and reference implementations
```

### The 8 Loop Patterns

| # | Pattern | Implementation | Status |
|---|---------|----------------|--------|
| 1 | Turn-Limited Multi-Condition | `TurnLimitedLoop` | Complete |
| 2 | Evaluator-Optimizer (Reflexion) | `EvaluatorOptimizerLoop` | Complete |
| 3 | Status-Based State Machine | `StateMachineLoop` | Complete |
| 4 | Finish Tool Detection | (subset of Pattern 1) | Complete |
| 5 | Pre-Planned Workflow | - | Future |
| 6 | Generator/Yield | - | Future |
| 7 | Exception-Driven | - | Future |
| 8 | Polling with Sleep | - | Future |

### Key Architectural Layers

```
┌─────────────────────────────────────────────────────────────────┐
│  GraphCompositionStrategy (Composition Layer)                    │
│  - Orchestrates multi-node workflows                            │
│  - Nodes can wrap AgentLoop instances                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ HOSTS (composition relationship)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  AgentLoop (Termination Patterns)                                │
│  TurnLimitedLoop | EvaluatorOptimizerLoop | StateMachineLoop    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ USES
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Spring AI ChatClient + ToolCallAdvisor                          │
│  (handles internal tool loop within a single invocation)        │
└─────────────────────────────────────────────────────────────────┘
```

### Core Types (harness-api)

- **`AgentLoop<R extends LoopResult>`** - Main interface for all loop implementations
- **`LoopState`** - Immutable record tracking turn count, tokens, cost, stuck detection
- **`LoopResult`** - Common contract for results with `runId()`, `output()`, `status()`, `reason()`
- **`TerminationStrategy`** - Pluggable termination logic with composition via `allOf()`
- **`ToolCallListener`** - Observability hook for tool execution events

### Turn Semantics (Critical)

Spring AI's `ToolCallAdvisor` handles the internal tool loop. What the taxonomy calls "turns" happens WITHIN a single `ChatClient.call()`. Our governance (limits, timeout) operates at the invocation level via `TurnLimitedToolCallAdvisor`.

## Design Principles

1. **Synchronous API**: User-facing APIs return results directly (no `Mono<>`)
2. **Config at Construction**: Builder pattern, config provided once at construction
3. **Bounded Generics**: `AgentLoop<R extends LoopResult>` for type-safe results
4. **No Adapters**: Direct Spring AI integration (ChatClient, ToolCallback, ChatResponse)
5. **Judge-Centric**: Termination logic encapsulated in judges/juries

## Code Conventions

- Java 21 with records, sealed interfaces, pattern matching, virtual threads
- Apache 2.0 License headers on all files
- No Lombok - use records and manual builders
- Config records: Spring-style builders with `builder()`, `toBuilder()`, `apply(Consumer)`
- Result records: Static factory methods (`success()`, `terminated()`, `failed()`)
- Test naming: `*Test.java` for unit tests, `*IT.java` for integration tests

## Key Files

### API Layer
- `harness-api/.../core/AgentLoop.java` - Main interface
- `harness-api/.../core/LoopState.java` - State tracking with stuck detection
- `harness-api/.../strategy/TerminationStrategy.java` - Termination logic

### Pattern Implementations
- `harness-patterns/.../turnlimited/TurnLimitedLoop.java` - Primary pattern (~400 lines)
- `harness-patterns/.../turnlimited/TurnLimitedToolCallAdvisor.java` - Enforces maxTurns at ChatClient level
- `harness-patterns/.../graph/GraphCompositionStrategy.java` - Multi-node workflow orchestration

### Tools
- `harness-tools/.../tools/BashTool.java` - Command execution with zt-exec
- `harness-tools/.../tools/ReadTool.java`, `WriteTool.java`, `EditTool.java` - File operations
- `harness-tools/.../tools/GlobTool.java`, `GrepTool.java` - Search operations

### Example
- `harness-examples/.../miniagent/MiniAgent.java` - 101-line SWE agent example

## Plans Directory

The `plans/` directory contains design documents and research:
- `VISION.md` - Project goals, patterns taxonomy, success criteria
- `DESIGN.md` - Design decision index
- `PLAN.md` - Implementation status and roadmap
- `supporting_repos/` - Reference implementations (mini-swe-agent, langgraph4j, etc.)

## Dependencies

- Spring AI 2.0-SNAPSHOT (ChatClient, ToolCallback, Advisors)
- spring-ai-agents-judge 0.1.0-SNAPSHOT (Jury/Verdict framework)
- Micrometer for observability
- zt-exec for process execution
