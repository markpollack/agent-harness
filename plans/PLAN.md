# Implementation Plan

## Status: Steps 1-4.6 Complete | OSS Migration Planned

**Last Updated**: 2026-01-13

---

# OSS MIGRATION PLAN: springaicommunity Donation

## Strategic Decision

**Rationale**: Agent harness patterns are commodity infrastructure. The value-add (supervisor, runtime, development-runtime) sits on top. Open-sourcing the harness:
- Grows the Spring AI community ecosystem
- Establishes springaicommunity as the home for agent patterns
- Allows Tuvium to focus on proprietary layers that use the harness

## Migration Overview

| Attribute | Before | After |
|-----------|--------|-------|
| **Repository** | `tuvium/spring-ai-agent-harnesses` | `markpollack/spring-ai-agent-harnesses` → `springaicommunity/spring-ai-agent-harnesses` |
| **GroupId** | `io.tuvium` | `org.springaicommunity.agents` |
| **Package** | `org.springaicommunity.agents.harness.*` | `org.springaicommunity.agents.harness.*` |
| **ArtifactId** | `spring-ai-agent-harnesses` | `spring-ai-agent-harnesses` |
| **License** | Proprietary | Apache 2.0 |

## Migration Steps

### Step M1: Package Rename (Local)

**Scope**: All Java files in all modules

| Module | From Package | To Package |
|--------|--------------|------------|
| harness-api | `org.springaicommunity.agents.harness.core` | `org.springaicommunity.agents.harness.core` |
| harness-patterns | `org.springaicommunity.agents.harness.patterns.*` | `org.springaicommunity.agents.harness.patterns.*` |
| harness-examples | `org.springaicommunity.agents.harness.examples.*` | `org.springaicommunity.agents.harness.examples.*` |

**Note**: harness-observability, harness-tracking, harness-llm, harness-sdlc, harness-test were removed.
See `learnings/EXPERIMENT-TRACKER-DECISION.md` for rationale on tracking approach.

**Approach**:
```bash
# For each module, rename directory structure
mv src/main/java/io/tuvium/harness src/main/java/org/springaicommunity/agents/harness

# Update all import statements
find . -name "*.java" -exec sed -i 's/io\.tuvium\.harness/org.springaicommunity.agents.harness/g' {} \;

# Update all package declarations
find . -name "*.java" -exec sed -i 's/package io\.tuvium\.harness/package org.springaicommunity.agents.harness/g' {} \;
```

### Step M2: Maven Coordinates Update

**Parent pom.xml changes**:
```xml
<!-- Before -->
<groupId>io.tuvium</groupId>
<artifactId>spring-ai-agent-harnesses</artifactId>

<!-- After -->
<groupId>org.springaicommunity.agents</groupId>
<artifactId>spring-ai-agent-harnesses</artifactId>
```

**All module pom.xml changes**:
- Update parent groupId reference
- Update internal dependency groupIds
- Add Apache 2.0 license header

**Files to update**:
- [ ] `pom.xml` (parent)
- [ ] `harness-api/pom.xml`
- [ ] `harness-patterns/pom.xml`
- [ ] `harness-examples/pom.xml`

### Step M3: Add License Headers

**Apache 2.0 header for all Java files**:
```java
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
```

**Approach**: Use `license-maven-plugin` or script to add headers to all `.java` files.

### Step M4: Add LICENSE File

**Task**: Add Apache 2.0 LICENSE file to repository root.

### Step M5: Update Documentation

**Files to update**:
- [ ] `README.md` - Update badges, installation instructions, package names
- [ ] `plans/VISION.md` - Update project name references
- [ ] `plans/DESIGN.md` - Update file paths
- [ ] `plans/PLAN.md` - This file (update paths after migration)
- [ ] All `learnings/*.md` files - Update package references

### Step M6: Update Dependencies on spring-ai-agents

**Verify compatibility**:
- [ ] `spring-ai-agents-judge` dependency still works with new package names
- [ ] No circular dependency issues
- [ ] Version alignment with other springaicommunity projects

### Step M7: Build Verification

**Tasks**:
- [ ] `mvn clean compile` - All modules compile
- [ ] `mvn test` - All 95 tests pass
- [ ] `mvn verify` - Integration tests pass
- [ ] `mvn package` - JARs build correctly with new coordinates

### Step M8: Code Review (GATE)

**CRITICAL**: All changes must be reviewed before any public push.

- [ ] Review all package renames for correctness
- [ ] Review all pom.xml changes
- [ ] Review license headers
- [ ] Review documentation updates
- [ ] Verify no proprietary code or secrets remain
- [ ] **HUMAN APPROVAL REQUIRED** before proceeding to M9

### Step M9: Repository Publication (AFTER REVIEW)

**Only after Step M8 approval**:
1. Create `spring-ai-agent-harnesses` repo in `markpollack` GitHub account
2. Initialize with Apache 2.0 LICENSE file
3. Add standard OSS files: CONTRIBUTING.md, CODE_OF_CONDUCT.md
4. Push reviewed code
5. Verify CI/CD works (GitHub Actions)
6. Create initial release tag (v0.1.0)
7. Transfer repository to `springaicommunity` org (or fork)
8. Archive `tuvium/spring-ai-agent-harnesses` with pointer to new location

### Step M10: Update Dependent Projects

**Projects that reference agent-harnesses**:
- [ ] `sdk-sync-agent` - Update dependency coordinates
- [ ] `tuvium-grand-plan` docs - Update references
- [ ] Any other Tuvium projects using harness

## Migration Checklist Summary

| Step | Description | Status |
|------|-------------|--------|
| M1 | Package rename (local) | Pending |
| M2 | Maven coordinates update | Pending |
| M3 | Add license headers | Pending |
| M4 | Add LICENSE file | Pending |
| M5 | Update documentation | Pending |
| M6 | Verify dependencies | Pending |
| M7 | Build verification | Pending |
| M8 | **Code review (GATE)** | Pending |
| M9 | Repository publication | Blocked on M8 |
| M10 | Update dependent projects | Blocked on M9 |

## Post-Migration: Tuvium Value-Add Layers

After migration, Tuvium proprietary work continues in separate repos:

| Tuvium Repo | Depends On | Purpose |
|-------------|------------|---------|
| `tuvium-runtime-core` | (standalone) | W&B-lite experiment tracking |
| `tuvium-agent-supervisor` | `spring-ai-agent-harnesses` | Outer refinement loops |
| `tuvium-development-runtime` | supervisor, runtime-core | Full SDLC orchestration |

---

> **Vision**: See `VISION.md` for project goals, patterns taxonomy, and success criteria.
> **Archive**: See `archive/SPRING-AI-REFACTORING-COMPLETED.md` for Phase 1-2 historical context.
> **Design**: See `design/` for design decisions and principles.
> **Learnings**: See `learnings/` for step-by-step implementation notes.

---

## Supporting Repositories

| Repository | Location | Purpose |
|------------|----------|---------|
| **mini-swe-agent** | `plans/supporting_repos/mini-swe-agent/` | Reference implementation - Python 100-line agent |
| **Spring AI** | `~/projects/spring-ai/` | Spring AI 2.0 source (our foundation) |
| **Agent Loop Taxonomy** | `~/research/AGENT-LOOP-TAXONOMY.md` | 8 patterns from 25+ repos |
| **Turn Semantics** | `~/research/appendix/SPRING-AI-TURN-SEMANTICS.md` | Spring AI vs taxonomy terminology |
| **Google ADK Java** | [github.com/google/adk-java](https://github.com/google/adk-java) | Multi-agent orchestration reference |

**Google ADK Architecture Note** (see `learnings/GOOGLE-ADK-JAVA-ANALYSIS.md`):
ADK operates at a **different layer** than agent-harness. ADK's workflow agents (SequentialAgent, ParallelAgent, LoopAgent) are multi-agent orchestrators—equivalent to our AgentClient layer. ADK's LlmAgent internal execution is where our TurnLimitedLoop operates. This validates our two-layer design.

**Line count comparison:**

| Component | mini-swe-agent (Python) | MiniAgent (Java) |
|-----------|-------------------------|------------------|
| Core agent | 122 | 101 |
| Config/env | 38 | 114 |
| Model/tools | 100 | 111 |
| **Total** | **260** | **326** |

Java is more verbose (type declarations, builders), but the **core agent is 21 lines shorter** because Spring AI's ChatClient+ToolCallAdvisor handles the tool loop internally.

**Key files:**
- Python: `plans/supporting_repos/mini-swe-agent/src/minisweagent/agents/default.py`
- Java: `harness-examples/src/main/java/.../miniagent/MiniAgent.java`

---

# Progress Overview

## Completed: Core Loop Patterns

| Step | Name | Status | Tests | Learnings |
|------|------|--------|-------|-----------|
| 1 | Foundation (harness-api) | ✅ Complete | 21 | - |
| 2 | Spring AI Direct Integration | ✅ Complete | - | `STEP-2-SPRING-AI-DIRECT.md` |
| 3 | Records and Builders | ✅ Complete | 37 | - |
| 3.5 | Tool Call Observability (Detour) | ✅ Complete | 15 | `archive/CHATCLIENT-TOOL-OBSERVABILITY-GAP.md` |
| 4 | MiniAgent First Deliverable | ✅ Complete | 37 | `STEP-4-MINIAGENT.md` |
| 4.5 | Invocation/Turn Semantics | ✅ Complete | - | `~/research/appendix/SPRING-AI-TURN-SEMANTICS.md` |
| 4.6 | MiniAgent Governance | ✅ Complete | 11 | `design/MINIAGENT-GOVERNANCE.md` |

**Implemented Loop Patterns** (3 of 5 needed):
- ✅ `TurnLimitedLoop` - Bounded exploration with multi-condition termination
- ✅ `EvaluatorOptimizerLoop` - Quality convergence with Judge feedback
- ✅ `StateMachineLoop` - Workflow stages with state transitions

## Roadmap: Prioritized by Value

| Priority | Step | Name | Category | Status |
|----------|------|------|----------|--------|
| **HIGH** | 5 | Core File Tools | Tools | ✅ Complete |
| **HIGH** | 8 | Execution Tools (BashTool) | Tools | ✅ Complete |
| **HIGH** | 9 | MCP Integration | Tools | Pending |
| **MEDIUM** | 9.5 | Graph-Defined Execution | Composition | ✅ Complete |
| **DEFERRED** | 6 | Tool Argument Augmenter | Control | Deferred |
| **DEFERRED** | 7 | Human-in-Loop Pattern | Pattern | Deferred |
| **LOW** | 10 | Spring Boot Auto-Configuration | Infra | Pending |
| **LOW** | 11 | ExploreTool (Code Intelligence) | Tools | Pending |
| **LOW** | 12 | Web Tools | Tools | Pending |
| **DEFERRED** | - | Tracking Integration | Infra | Blocked on AgentClient |

**Out of Scope**:
- ❌ GOAP Planning - See "Why Not GOAP" design decision below
- ❌ Cognitive Pipeline Loop - Planning belongs in AgentClient (see `learnings/PLANNING-AS-FIRST-CLASS.md`)

**Total Tests**: 227 passing (21 harness-api + 132 harness-patterns + 36 harness-tools + 38 harness-examples)

---

# Completed Work Summary

## Step 1: Foundation
- **harness-api module**: Core interfaces
  - `AgentLoop<R extends LoopResult>` - Central abstraction
  - `LoopResult`, `LoopStatus`, `TerminationReason` - Result types
  - `LoopState` - Immutable state with stuck detection
  - `TerminationStrategy` - Pattern-specific termination

## Step 2: Spring AI Direct Integration
- Direct Spring AI adoption (no adapter layer)
- All loops use `ChatClient`, `ChatResponse`, `ToolCallback` directly
- Synchronous API (no Reactor in user-facing code)
- TOP 3 patterns: `TurnLimitedLoop`, `EvaluatorOptimizerLoop`, `StateMachineLoop`

## Step 3: Records and Builders
- `TurnLimitedConfig` with full Spring-style builder (toBuilder, apply, copy)
- `TurnLimitedResult` with factory methods (success, terminated, failed)
- Comprehensive unit tests for config, result, state, and loop

## Detour 3.5: Tool Call Observability
- `ToolCallListener` interface for tool execution events
- `ToolCallObservationHandler` bridges Micrometer to listeners
- No need to drop from ChatClient to ChatModel

## Step 4: MiniAgent
- 101-line SWE agent (21 lines shorter than Python mini-swe-agent)
- Uses ChatClient + ToolCallAdvisor (Spring AI handles tool loop)
- Observability via Micrometer ObservationRegistry
- See `design/spring-ai-agent-architecture.md` for architecture
- See `learnings/STEP-4-MINIAGENT.md` for details

---

# Completed Design Decisions

## Synchronous API (No Reactor)

**Status**: ✅ Complete

Agent loops are inherently **sequential** (turn → tool → turn). Reactor provides no benefit:

| Reactor Feature | Needed? | Decision |
|-----------------|---------|----------|
| Non-blocking I/O | No | Use virtual threads |
| Backpressure | No | One turn at a time |
| Composition | No | Simple while loop |

**Result**: `AgentLoop.execute()` returns result directly (no `Mono<>`).

## Bounded Type Parameter

**Status**: ✅ Complete

Changed from `AgentLoop<S>` to `AgentLoop<R extends LoopResult>`:

| Before | After |
|--------|-------|
| `TurnLimitedLoop<S>` | `TurnLimitedLoop implements AgentLoop<TurnLimitedResult>` |
| Required `SummaryBuilder<S>` | Returns result directly |
| Generic summary type | Pattern-specific result record |

See `design/agentloop-result-types.md` for full design.

---

# STEP 3: Records, Builders, and TurnLimitedLoop Tests ✅ COMPLETE

## Goal
1. Implement config records with Spring-style builders per `design/records-and-builders.md`
2. Add unit tests for TurnLimitedLoop (the loop to be used in Step 4 MiniAgent)

## Entry Conditions
- [x] Bounded type parameter design complete (`AgentLoop<R extends LoopResult>`)
- [x] Result record interfaces defined (`LoopResult`, `LoopStatus`, `TerminationReason`)
- [x] Design document reviewed: `design/records-and-builders.md`

## Implementation Tasks

### Part A: Config Records (Builder Enhancement)

| Record | Basic Builder | `toBuilder()` | `apply(Consumer)` | Tests |
|--------|---------------|---------------|-------------------|-------|
| `TurnLimitedConfig` | ✅ | ✅ | ✅ | ✅ 20 tests |
| `EvaluatorOptimizerConfig` | Pending | Pending | Pending | Pending |
| `StateMachineConfig` | Pending | Pending | Pending | Pending |

Each config record needs:
- [x] Builder class with defaults
- [x] `builder()` static method
- [x] `toBuilder()` for copy-on-write
- [x] `apply(Consumer<Builder>)` for reusable configuration
- [x] `copy()` for builder cloning
- [x] Unit tests for builder defaults and validation

### Part B: Result Records (Factory Methods)

| Record | Factory Methods | Tests |
|--------|-----------------|-------|
| `TurnLimitedResult` | ✅ `success()`, `terminated()`, `failed()` | ✅ 12 tests |
| `EvaluatorOptimizerResult` | Pending | Pending |
| `StateMachineResult` | Pending | Pending |

Each result record needs:
- [x] Static factory methods for framework use
- [x] Convenience query methods (`isSuccess()`, `wasStuck()`, etc.)
- [x] Unit tests for factory methods

### Part C: State Records

| Record | Pattern | Status |
|--------|---------|--------|
| `LoopState` | ✅ `initial()` + immutable update methods | ✅ 21 tests |
| `AgentState` | Constants + `of()` factory | Future (StateMachine) |
| `TransitionResult` | `stay()`, `transitionTo()`, `complete()`, `fail()` | Future (StateMachine) |

### Part D: TurnLimitedLoop Unit Tests

| Test Category | Description | Status |
|---------------|-------------|--------|
| `TurnLimitedConfigTest` | Builder defaults, validation, toBuilder | ✅ 20 tests |
| `TurnLimitedResultTest` | Factory methods, query methods | ✅ 12 tests |
| `LoopStateTest` | initial(), completeTurn(), abort(), stuck detection | ✅ 21 tests |
| `TurnLimitedLoopTest` | Loop execution with mock ChatClient | ✅ 11 tests |

Test scenarios for TurnLimitedLoop:
- [x] Loop terminates on max turns
- [x] Loop terminates on finish tool
- [x] Loop terminates on no tool calls
- [x] Loop terminates on abort signal
- [x] Token and cost tracking works
- [x] Listener notifications fire

## Exit Criteria
- [x] TurnLimitedConfig has full Spring-style builder with tests
- [x] TurnLimitedResult factory methods have tests
- [x] LoopState has unit tests
- [x] TurnLimitedLoop has unit tests with mock ChatClient
- [x] Build passes with `mvn clean verify`
- [ ] **Write**: `learnings/STEP-3-RECORDS-BUILDERS.md`

---

# DETOUR 3.5: Tool Call Observability via Micrometer ✅ COMPLETE

## Context
While investigating how to track individual tool calls for the MiniAgent, we discovered that dropping to `ChatModel` (away from `ChatClient`) would lose critical features (advisors, interceptors, fluent API). This detour explored ChatClient's gaps and found a clean solution using Spring AI's built-in Micrometer observability.

## Discovery
- **ToolCallAdvisor** brings user-controlled tool execution to ChatClient (no need to drop to ChatModel)
- **DefaultToolCallingManager** wraps each tool execution in Micrometer `.observe()` with `ToolCallingObservationContext`
- **Solution**: Register a custom `ObservationHandler<ToolCallingObservationContext>` to receive callbacks for every tool call

## Implementation

| Component | Description | Status |
|-----------|-------------|--------|
| `ToolCallListener` | Interface for tool call events | ✅ in harness-api |
| `ToolCallObservationHandler` | Bridges Micrometer to ToolCallListener | ✅ in harness-patterns |
| `ToolCallObservationHandlerTest` | Unit tests | ✅ 15 tests |

## Usage Pattern
```java
// Create in-memory observation registry
ObservationRegistry registry = ObservationRegistry.create();
registry.observationConfig()
    .observationHandler(new ToolCallObservationHandler(listeners));

// Configure ToolCallingManager with registry
ToolCallingManager manager = DefaultToolCallingManager.builder()
    .observationRegistry(registry)
    .build();

// Use ToolCallAdvisor with ChatClient
var advisor = ToolCallAdvisor.builder()
    .toolCallingManager(manager)
    .build();
```

## Exit Criteria
- [x] ToolCallObservationHandler created
- [x] Unit tests verify observability works
- [x] No need to drop to ChatModel
- [x] Solution documented in `CHATCLIENT-TOOL-OBSERVABILITY-GAP.md`

---

# STEP 4: MiniAgent First Deliverable ✅ COMPLETE

## Goal
Validate framework design with a ~100 line agent equivalent to mini-swe-agent.

## Entry Conditions
- [x] **Read**: `CHATCLIENT-TOOL-OBSERVABILITY-GAP.md` (Detour 3.5 - tool call tracking)
- [x] **Read**: `~/research/spring-ai-tool-to-toolcallback-conversion.md`
- [x] **Read**: `~/research/spring-ai-in-memory-chat-memory.md`
- [x] **Read**: `~/research/PROCESS-EXECUTION-BEST-PRACTICES.md`
- [x] **Analyzed**: `plans/supporting_repos/mini-swe-agent/tests/` (test patterns)
- [x] TurnLimitedLoop compiles and uses ChatClient directly
- [x] Reviewed mini-swe-agent source
- [x] Synchronous API (no Reactor)
- [x] Simplified generics (`AgentLoop<R extends LoopResult>`)
- [x] Tool call observability via Micrometer (Detour 3.5 complete)

## Research Tasks ✅ COMPLETE

| Task | Status | Source |
|------|--------|--------|
| Analyze mini-swe-agent tests | ✅ | `plans/supporting_repos/mini-swe-agent/tests/` |
| Tool callback conversion | ✅ | `~/research/spring-ai-tool-to-toolcallback-conversion.md` |
| ProcessBuilder best practices | ✅ | `~/research/PROCESS-EXECUTION-BEST-PRACTICES.md` |
| ChatMemory integration | ✅ | `~/research/spring-ai-in-memory-chat-memory.md` |

### Key Findings

**Tool Callbacks:**
- Use `ToolCallbacks.from(myToolsObject)` - simplest API
- `@Tool` annotation with `description`, optional `returnDirect`
- `@ToolParam` for parameter descriptions
- Spring autoconfiguration handles registration automatically

**Chat Memory:**
- `MessageWindowChatMemory.builder().build()` - default 20 messages
- `MessageChatMemoryAdvisor` adds messages as structured objects
- Use `ChatMemory.CONVERSATION_ID` context param for per-request sessions

**Process Execution:**
- Use zt-exec for one-shot commands (built-in timeout)
- ProcessBuilder for bidirectional communication
- Always drain stdout/stderr concurrently to prevent deadlock
- Graceful termination: `destroy()` → wait 5s → `destroyForcibly()`

**mini-swe-agent Test Patterns:**
- `DeterministicModel` - mock that returns predetermined outputs
- Tests: step limit, cost limit, timeout, format errors, message history
- Finish signal: `COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT`
- `LocalEnvironment` wraps subprocess execution with timeout

## Implementation Tasks

| Component | Description | Approach |
|-----------|-------------|----------|
| `MiniAgentConfig` | Record with systemPrompt, maxTurns, costLimit | Spring-style builder |
| `MiniAgent` | Uses TurnLimitedLoop + ChatMemory | ChatClient with advisors |
| `BashTool` | Command execution with timeout | zt-exec `ProcessExecutor` |
| `SubmitTool` | Finish tool (returnDirect=true) | `@Tool(returnDirect=true)` |
| `DeterministicChatModel` | Mock for testing | Implement `ChatModel` interface |
| `MiniAgentTest` | Port key tests from mini-swe-agent | JUnit 5 + Mockito |

## Memory Integration

```java
// Create memory with window limit
var memory = MessageWindowChatMemory.builder()
    .maxMessages(50)
    .build();

// Create advisor with conversation ID
var memoryAdvisor = MessageChatMemoryAdvisor.builder(memory)
    .conversationId(runId)
    .build();

// ChatClient with memory + tools
chatClient.prompt()
    .advisors(memoryAdvisor)
    .tools(bashTool, submitTool)
    .call();
```

## Tool Implementation Pattern

```java
public class MiniAgentTools {
    @Tool(description = "Execute a bash command")
    public String bash(
            @ToolParam(description = "The command to execute") String command) {
        return new ProcessExecutor()
            .command("bash", "-c", command)
            .timeout(30, TimeUnit.SECONDS)
            .readOutput(true)
            .execute()
            .outputUTF8();
    }

    @Tool(description = "Submit final answer", returnDirect = true)
    public String submit(
            @ToolParam(description = "The final answer") String answer) {
        return answer;  // returnDirect=true stops the loop
    }
}

// Convert to callbacks
List<ToolCallback> tools = Arrays.asList(
    ToolCallbacks.from(new MiniAgentTools())
);
```

## Exit Criteria
- [x] MiniAgent compiles and runs with mock ChatClient
- [x] Total user-facing code ~100 lines (101 lines)
- [x] Works with any Spring AI ChatModel (Claude, GPT, etc.)
- [x] **Write**: `learnings/STEP-4-MINIAGENT.md`

---

# STEP 4.5: Invocation/Turn Semantics Alignment ✅ COMPLETE

## Goal
Align terminology with Spring AI reality and add meaningful metrics to MiniAgentResult.

## Context
Spring AI's `ToolCallAdvisor` handles the internal tool loop. What the taxonomy calls "turns" happens WITHIN a single `ChatClient.call()`. Our governance operates at the "invocation" level.

**Decision**: Option A + D Hybrid (interim, superseded by Step 4.6)
- Accept semantic shift: our "turn" = one `ChatClient.call()` invocation
- Add `toolCallsExecuted` to results (tracked via ToolCallListener)
- Keep MiniAgent simple - it's the 100-line showcase

## Exit Criteria
- [x] MiniAgentResult includes `toolCallsExecuted`
- [x] CountingToolCallListener counts tool executions
- [x] Renamed `turnsCompleted` → `invocations` (will revert in Step 4.6)
- [x] Tests verify tool call counting
- [x] Update `SPRING-AI-TURN-SEMANTICS.md` with design

**Note**: Step 4.6 will revert terminology once we control the turn loop via TurnLimitedToolCallAdvisor.

---

# STEP 4.6: MiniAgent Governance

## Goal
Add real turn limiting to MiniAgent by subclassing Spring AI's ToolCallAdvisor.

## Context
MiniAgent currently has `maxTurns` in config but **ignores it**. Spring AI's ToolCallAdvisor runs until the LLM stops calling tools. This is a design smell - our "first trivial agent" should use our patterns.

**Decision**: Subclass ToolCallAdvisor to add turn limiting (see `design/MINIAGENT-GOVERNANCE.md`)

## Implementation Plan

**Sequencing**: Implement first, rename terminology after validation.

| Sub-step | Task | Module | Status |
|----------|------|--------|--------|
| 4.6a | Create `TurnLimitedToolCallAdvisor` | harness-patterns | ✅ |
| 4.6b | Create `TurnLimitExceededException` | harness-patterns | ✅ |
| 4.6c | Add unit tests for turn limiting | harness-patterns | ✅ |
| 4.6d | Update MiniAgent to use new advisor | harness-examples | ✅ |
| 4.6e | Add integration test proving `maxTurns` works | harness-examples | ✅ |
| 4.6f | Revert terminology: `invocations` → `turnsCompleted` | harness-examples | ✅ |
| 4.6g | Remove `invocations` field, keep `toolCallsExecuted` | harness-examples | ✅ |
| 4.6h | Update SPRING-AI-TURN-SEMANTICS.md | research | ✅ |

**All sub-steps complete.** MiniAgent now enforces maxTurns via TurnLimitedToolCallAdvisor.

## Key Design

```java
public class TurnLimitedToolCallAdvisor extends ToolCallAdvisor {
    private final int maxTurns;
    private final ThreadLocal<Integer> turnCount = ThreadLocal.withInitial(() -> 0);

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        int current = turnCount.get() + 1;
        turnCount.set(current);
        if (current > maxTurns) {
            throw new TurnLimitExceededException(maxTurns, current, response);
        }
        return super.doAfterCall(response, chain);
    }
}
```

## Exit Criteria
- [ ] TurnLimitedToolCallAdvisor created in harness-patterns
- [ ] MiniAgent uses TurnLimitedToolCallAdvisor
- [ ] `maxTurns` config actually enforced
- [ ] Terminology reverted to `turnsCompleted`
- [ ] SPRING-AI-TURN-SEMANTICS.md updated with final design

---

# Design Decision: Why Not GOAP

## Status: ❌ Out of Scope for agent-harness

**Decision**: GOAP (Goal-Oriented Action Planning) is NOT appropriate for LLM agent harnesses.

## The Enterprise "Control" Question

When enterprises ask for "control" over AI agents, they are asking:
- Will the system stay within approved boundaries?
- Can we explain why it took an action?
- Can we audit, reproduce, and roll back outcomes?

GOAP *sounds* like the answer because actions are enumerable and sequences are explicit.
**But GOAP does not address the real risk surface.**

## Why GOAP Fails for LLM Agents

| Characteristic | GOAP (Game AI) | LLM Agents |
|----------------|----------------|------------|
| **State space** | Enumerable booleans | Infinite (natural language) |
| **Actions** | Fixed, predefined | Emergent from LLM |
| **Effects** | Deterministic | Probabilistic |
| **World model** | Complete, observable | Partial, inferred |
| **Explanation** | "Action X was chosen" | Need: "Here is WHY" |

**Key insight**: GOAP controls **what is allowed**. It cannot explain **why** a tool was invoked.

## The Real Risk Surface: Side Effects

The actual risk in LLM agents is **side effects**: tool calls, file writes, network access.

GOAP does nothing to:
- Capture the rationale for a tool call
- Explain what evidence triggered the decision
- Record what the model expected to happen

## The Better Control Model: Rationale Capture

Spring AI's **Tool Argument Augmenter** provides what enterprises actually need:

For every tool call, capture:
- **Why** the tool was chosen
- **What evidence** triggered it
- **What outcome** the model expected
- **Confidence** in the choice
- **Run/step metadata**

This information is:
- Explicitly produced by the model
- Captured before the side effect happens
- Machine-verifiable and auditable

## The Control Reframe

> **Planning-based control** restricts what an agent is allowed to do.
> **Evidence-based control** proves what an agent actually did—and why.

For modern AI systems operating under uncertainty, evidence-based control is the stronger guarantee.

## What agent-harness Provides Instead

| Mechanism | Description |
|-----------|-------------|
| **Bounded execution** | Turn limits, token budgets, timeouts |
| **Tool allowlists** | Only approved tools available |
| **LoopListener events** | Observable execution without data capture |
| **Judge-based evaluation** | Empirical validation of outcomes |
| **SLF4J logging** | Debug visibility |

Tracking and rationale capture happen at the **outer loop** (AgentClient/supervisor level), not inside execution patterns.

## Academic Note

A minimal GOAP implementation exists in a separate project (`planner-goap`) for academic completeness.
It demonstrates that GOAP is a degenerate case of Judge-centric architecture, but is **not recommended** for production LLM agents.

---

# STEP 5: Core File Tools

## Goal
Implement essential SDLC tools for file operations.

## Entry Conditions
- [ ] **Read**: `learnings/STEP-4-MINIAGENT.md` (tool implementation patterns)

## Implementation Tasks
| Tool | Description | Approach |
|------|-------------|----------|
| `ReadTool` | Read file with line limits | Java NIO `Files.readString` |
| `WriteTool` | Create/overwrite file | Java NIO, verify parent exists |
| `EditTool` | String replacement | Exact match replacement |
| `GlobTool` | Pattern matching | `Files.newDirectoryStream` |
| `GrepTool` | Content search | Shell out to `rg` (ripgrep) |

## Exit Criteria
- [ ] All tools compile and have unit tests
- [ ] Tools work with TurnLimitedLoop
- [ ] **Write**: `learnings/STEP-5-FILE-TOOLS.md`

---

# STEP 6: Tool Argument Augmenter Integration

## Goal
Integrate Spring AI's Tool Argument Augmenter to capture rationale for every tool call. This is the **key control mechanism** that replaces GOAP-style procedural control.

## Context
From the "Why Not GOAP" analysis: enterprises need evidence-based control, not planning-based control. The Tool Argument Augmenter provides this by capturing:
- **Why** the tool was chosen
- **What evidence** triggered the decision
- **What outcome** the model expected
- **Confidence** in the choice

This information is captured **before** the side effect happens and persisted for audit.

## Entry Conditions
- [ ] **Read**: `learnings/STEP-5-FILE-TOOLS.md`
- [ ] **Read**: Spring AI Tool Augmenter documentation
- [ ] File tools working

## Implementation Tasks
- [ ] Add `spring-ai-tool-search-tool` dependency (includes augmenter)
- [ ] Create `RationaleCapturingAdvisor` that wraps tool calls
- [ ] Define augmentation schema (reasoning, evidence, expectedOutcome, confidence)
- [ ] Store captured rationales in LoopState or dedicated store
- [ ] Test that every tool call captures structured rationale

## Augmentation Schema

```java
record ToolRationale(
    String reasoning,        // Why this tool was chosen
    String evidence,         // What triggered this decision
    String expectedOutcome,  // What the model expects to happen
    String confidence,       // high/medium/low
    Map<String, Object> metadata  // Run ID, step, etc.
) {}
```

## Exit Criteria
- [ ] Every tool call in TurnLimitedLoop captures rationale
- [ ] Rationale is accessible for audit/replay
- [ ] Works with all existing tools (file, bash, etc.)
- [ ] **Write**: `learnings/STEP-6-TOOL-AUGMENTER.md`

---

# STEP 7: Human-in-Loop Pattern

## Goal
Implement approval gates for high-stakes operations. Critical for enterprise adoption.

## Context
From SDLC-AGENT-PATTERNS.md, human-in-loop is needed for:
- Database migrations (approve schema changes)
- Security changes (approve permission modifications)
- API breaking changes (approve deprecations)
- Production deployments (approve release)

## Pattern Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    HUMAN-IN-LOOP                                 │
├─────────────────────────────────────────────────────────────────┤
│  1. GENERATE (agent proposes change)                            │
│     └── "I want to drop column 'legacy_id' from users table"    │
├─────────────────────────────────────────────────────────────────┤
│  2. ESCALATE (if change matches escalation criteria)            │
│     └── Schema drops, permission changes, breaking API changes  │
├─────────────────────────────────────────────────────────────────┤
│  3. AWAIT APPROVAL (pause execution, notify human)              │
│     └── Slack/email notification, timeout handling              │
├─────────────────────────────────────────────────────────────────┤
│  4. PROCEED or REVISE                                           │
│     └── Approved → execute | Rejected → agent revises proposal  │
└─────────────────────────────────────────────────────────────────┘
```

## Entry Conditions
- [ ] **Read**: `learnings/STEP-6-TOOL-AUGMENTER.md`
- [ ] Tool Argument Augmenter working

## Implementation Tasks

| Component | Description |
|-----------|-------------|
| `HumanInLoopLoop` | Main loop with approval gates |
| `EscalationCriteria` | Predicate that triggers human review |
| `ApprovalHandler` | Interface for notification (Slack, email, etc.) |
| `ApprovalResult` | APPROVED, REJECTED, TIMEOUT |
| `RevisionStrategy` | How agent responds to rejection |

## API Design

```java
HumanInLoopLoop.builder()
    .generator(migrationAgent::propose)
    .escalateWhen(change -> change.isBreaking() || change.affectsProduction())
    .approvalHandler(slackNotifier)
    .approvalTimeout(Duration.ofHours(24))
    .onApproved(executor::apply)
    .onRejected(agent::revise)
    .maxRevisions(3)
    .build()
    .execute(migrationTask);
```

## Exit Criteria
- [ ] `HumanInLoopLoop` compiles and has unit tests
- [ ] Escalation criteria correctly triggers approval flow
- [ ] Timeout handling works (auto-reject or auto-approve based on config)
- [ ] Rejection triggers revision cycle
- [ ] **Write**: `learnings/STEP-7-HUMAN-IN-LOOP.md`

---

# STEP 8: Execution Tools

## Goal
Implement bash and code execution tools with proper sandboxing.

## Entry Conditions
- [ ] **Read**: `learnings/STEP-4-MINIAGENT.md` (ProcessBuilder patterns)
- [ ] **Read**: `learnings/STEP-7-HUMAN-IN-LOOP.md`

## Implementation Tasks
| Tool | Description | Approach |
|------|-------------|----------|
| `BashTool` (full) | Timeout, output limits, sandbox | ProcessBuilder + optional isolation |
| `CodeExecutionTool` | Python/JS sandbox | E2B API primary, Docker fallback |

## Exit Criteria
- [ ] BashTool handles timeout and large output
- [ ] CodeExecutionTool works with E2B
- [ ] **Write**: `learnings/STEP-8-EXECUTION.md`

---

# STEP 9: MCP Integration

## Goal
Enable MCP tools as first-class ToolCallbacks.

## Entry Conditions
- [ ] **Read**: `learnings/STEP-4-MINIAGENT.md` (tool patterns)
- [ ] **Read**: `learnings/STEP-8-EXECUTION.md`

## Implementation Tasks
- [ ] Use `SyncMcpToolCallbackProvider` to get MCP tools
- [ ] Combine MCP tools with regular tools in loop
- [ ] Test with filesystem MCP server

## Exit Criteria
- [ ] MCP tools work in TurnLimitedLoop
- [ ] **Write**: `learnings/STEP-9-MCP.md`

---

# STEP 9.5: Graph-Defined Execution (Composition Layer)

## Goal
Add graph-defined execution as a composition layer ABOVE existing AgentLoop patterns. This is NOT Pattern 9 - it's an orthogonal dimension (Axis 2: Execution Definition Model).

## Context
Following the Two-Axis Taxonomy Model (`learnings/TWO-AXIS-TAXONOMY-MODEL.md`), agent architectures are classified on two orthogonal axes:
- **Axis 1**: Termination Pattern (Turn-Limited, Finish Tool, State Machine, etc.) - the 8 patterns
- **Axis 2**: Execution Definition Model (Imperative, Generator, Graph-Defined, Event-Driven)

Graph-defined execution can HOST loops inside nodes. Our loops become building blocks.

**Key Design Principle**: `GraphStrategy` does NOT implement `AgentLoop`. It's a composition layer.

```
┌─────────────────────────────────────────────────────────────────┐
│  GraphStrategy (Composition Layer) - NEW                         │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │  GraphNode  │───>│  GraphNode  │───>│  GraphNode  │         │
│  │  (function) │    │  (AgentLoop)│    │  (function) │         │
│  └─────────────┘    └─────────────┘    └─────────────┘         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ HOSTS (composition relationship)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  AgentLoop (Termination Patterns) - EXISTING                     │
│  TurnLimitedLoop | EvaluatorOptimizerLoop | StateMachineLoop    │
└─────────────────────────────────────────────────────────────────┘
```

## Entry Conditions
- [x] **Read**: `learnings/TWO-AXIS-TAXONOMY-MODEL.md` (orthogonal dimensions)
- [x] **Read**: `learnings/KOOG-GRAPH-ARCHITECTURE-ANALYSIS.md` (Koog code analysis)
- [x] **Read**: `learnings/AGENT-FAILURE-TAXONOMY.md` (graph topology failures)
- [ ] Core loops (TurnLimited, EvaluatorOptimizer, StateMachine) implemented and tested

## Implementation Tasks

### Part A: Core Interfaces

| File | Description | Status |
|------|-------------|--------|
| `GraphNode.java` | Node interface + factory methods | Pending |
| `FunctionGraphNode.java` | Function-based node impl | Pending |
| `LoopGraphNode.java` | AgentLoop wrapper node impl | Pending |
| `GraphEdge.java` | Edge record with condition | Pending |
| `GraphContext.java` | Execution context | Pending |
| `GraphResult.java` | Graph-specific result (NOT LoopResult) | Pending |
| `GraphExecutionException.java` | Graph topology exceptions | Pending |

### Part B: Strategy and Builder

| File | Description | Status |
|------|-------------|--------|
| `GraphStrategy.java` | Main execution container | Pending |
| `GraphStrategyBuilder.java` | Fluent DSL builder | Pending |

## API Design

```java
// Create existing loops
TurnLimitedLoop codingLoop = TurnLimitedLoop.builder()
    .maxTurns(10)
    .build();

TurnLimitedLoop testLoop = TurnLimitedLoop.builder()
    .maxTurns(5)
    .build();

// Compose into graph strategy
GraphStrategy<String, String> strategy = GraphStrategy.<String, String>builder("coding-agent")
    .startNode("start")
    .finishNode("finish")

    // Simple function node
    .node("plan", (ctx, input) -> "Plan: " + input)

    // Node wrapping an AgentLoop
    .loopNode("code", codingLoop, chatClient, codingTools)
    .loopNode("test", testLoop, chatClient, testTools)

    // Edges with conditions
    .edge("start").to("plan")
    .edge("plan").to("code")
    .edge("code").to("test")
    .edge("test").to("finish").when(TestResult::passed)
    .edge("test").to("code").when(TestResult::failed)  // Cycle!

    .maxIterations(50)
    .build();

// Execute
GraphResult<String> result = strategy.execute("Implement OAuth");
```

## Test Plan

| Test Category | Description | Status |
|---------------|-------------|--------|
| `GraphNodeTest` | Function node, loop node execution | Pending |
| `GraphEdgeTest` | Condition filtering, transformation | Pending |
| `GraphStrategyTest` | Linear graph, cyclic graph, max iterations | Pending |
| `GraphStrategyBuilderTest` | DSL builder, edge DSL | Pending |
| `GraphStuckNodeTest` | Graph topology failure detection | Pending |
| Integration | Graph with TurnLimitedLoop nodes | Pending |

## What We're NOT Building

1. ❌ GraphStrategy implementing AgentLoop
2. ❌ External graph library dependency (JGraphT, Guava Graph)
3. ❌ Graph persistence or database
4. ❌ Distributed graph traversal
5. ❌ GOAP or algorithmic planning

## Future Consideration: Event-Driven Pattern

LlamaIndex Workflows rejected graph-defined execution in favor of **event-driven** patterns:

> "Other frameworks have attempted to solve this with DAGs but these have limitations: Logic like loops and branches needed to be encoded into edges, which made them hard to read and understand."

Their event-driven approach uses decorated step functions that emit/receive events, with the framework handling routing.

**Research Notes**:
- LlamaIndex Workflows: `@step` decorator, event-based routing
- MetaGPT: Pub-sub message passing between agents
- Both are captured in our Two-Axis Taxonomy Model (Pattern 7 + Axis 2 Event-Driven)

**Action Item**: Research LlamaIndex Workflows source code (`plans/supporting_repos/llama_index/` when cloned) before considering event-driven as an alternative to graph-defined. Event-driven may be a better fit for some use cases, particularly when:
- Step dependencies are implicit (via event types)
- Control flow is more reactive than planned
- Workflow structure needs runtime flexibility

See `learnings/TWO-AXIS-TAXONOMY-MODEL.md` for how event-driven fits into our taxonomy.

## Koog Relationship (Design Rationale)

JetBrains Koog uses fine-grained nodes (1 node = 1 LLM call). Our GraphStrategy allows coarse-grained nodes (1 node = N LLM calls via wrapped AgentLoop).

| Aspect | Koog | agent-harness GraphStrategy |
|--------|------|----------------------------|
| Node granularity | 1 node = 1 LLM call | 1 node = N LLM calls (flexible) |
| Cycles | Graph edges only | Graph edges + inner loops |
| Nested loops | Not supported | Supported (AgentLoop inside node) |

**Koog is a degenerate case** of our design where every node executes exactly once.

## Exit Criteria
- [ ] `GraphStrategy` is separate from `AgentLoop` hierarchy
- [ ] Existing loops can be wrapped as graph nodes
- [ ] Graph topology failures (stuck node) are distinct from loop failures
- [ ] Two-axis taxonomy is preserved
- [ ] All tests pass (~30 tests)
- [ ] `mvn clean verify` passes
- [ ] **Write**: `learnings/STEP-9.5-GRAPH-EXECUTION.md`

---

# STEP 10: Spring Boot Auto-Configuration

## Goal
Create harness-boot starter for zero-config setup.

## Entry Conditions
- [ ] **Read**: `learnings/STEP-9-MCP.md`

## Implementation Tasks
- [ ] Create `harness-boot` module
- [ ] `HarnessAutoConfiguration` with conditional beans
- [ ] Auto-configure TurnLimitedLoop, ObservabilityProvider
- [ ] Integration tests with mock ChatClient

## Exit Criteria
- [ ] Spring Boot app auto-wires loop with `@Autowired`
- [ ] Configuration via `application.properties`
- [ ] **Write**: `learnings/STEP-10-BOOT-STARTER.md`

---

# STEP 11: ExploreTool (Code Intelligence)

## Goal
Implement code intelligence beyond embeddings.

## Entry Conditions
- [ ] **Read**: `learnings/STEP-5-FILE-TOOLS.md`
- [ ] **Read**: `learnings/STEP-10-BOOT-STARTER.md`
- [ ] **Research**: scip-java for Java symbol indexing

## Implementation Tasks
| Capability | Implementation |
|------------|----------------|
| `find_files(glob)` | Ripgrep wrapper |
| `get_symbols(query)` | SCIP index |
| `goto_definition(symbol)` | SCIP lookup |
| `find_references(symbol)` | SCIP lookup |
| `semantic_query(nl)` | Hybrid: vector + symbol + rerank |

## Exit Criteria
- [ ] ExploreTool answers "where is X defined?" questions
- [ ] Works on Maven/Gradle Java projects
- [ ] **Write**: `learnings/STEP-11-EXPLORE.md`

---

# STEP 12: Web Tools

## Goal
Implement web search and fetch with SaaS fallbacks.

## Entry Conditions
- [ ] **Read**: `learnings/STEP-11-EXPLORE.md`

## Implementation Tasks
| Tool | Primary | Fallback |
|------|---------|----------|
| `WebSearchTool` | Exa API | Brave API |
| `WebFetchTool` | Firecrawl API | Jsoup |

## Exit Criteria
- [ ] Web search returns AI-optimized results
- [ ] Web fetch handles JavaScript-heavy pages
- [ ] **Write**: `learnings/STEP-12-WEB-TOOLS.md`

---

# Reference: Build vs Buy Decisions

## SaaS (Best-of-Breed)
| Tool | Provider | Why |
|------|----------|-----|
| Web Search | [Exa.ai](https://exa.ai) | Semantic search, <450ms |
| Web Scraping | [Firecrawl](https://firecrawl.dev) | 96% web coverage |
| Code Sandbox | [E2B](https://e2b.dev) | 150ms boot, OSS |

## Build (Spiritual Ports)
| Tool | Inspiration | Approach |
|------|-------------|----------|
| File tools | Claude Code | Java NIO |
| GrepTool | Ripgrep | Shell out to `rg` |
| ExploreTool | scip-java + Glean | Symbol index + hybrid search |

---

*Last updated: 2026-01-13*
