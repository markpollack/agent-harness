# Design Decisions

This document indexes all design decisions for the spring-ai-agent-harnesses project.

---

## Core API Design

### AgentLoop Result Types

**Decision**: Use bounded type parameter `AgentLoop<R extends LoopResult>` instead of unbounded `AgentLoop<S>`.

**Status**: Implemented (2025-12-21)

**Document**: [design/agentloop-result-types.md](design/agentloop-result-types.md)

**Summary**:
- Each loop returns its specific result type (e.g., `TurnLimitedResult`)
- Common contract via `LoopResult` interface
- Pattern-specific data in result records (trials, transitions, verdicts)
- No `SummaryBuilder` boilerplate required
- Extensible for user-defined loops

**Key Files**:
- `harness-api/.../core/LoopResult.java` - Common interface
- `harness-api/.../core/LoopStatus.java` - Execution status enum
- `harness-api/.../core/TerminationReason.java` - Termination reasons
- `harness-patterns/.../TurnLimitedResult.java`
- `harness-patterns/.../EvaluatorOptimizerResult.java`
- `harness-patterns/.../StateMachineResult.java`

---

### Synchronous API (No Reactor)

**Decision**: User-facing APIs are synchronous. No `Mono<>` or reactive types.

**Status**: Implemented (2025-12-20)

**Rationale**:
- Agent loops are inherently sequential (turn → tool → turn)
- Reactor adds complexity without benefit for this use case
- Java 21 virtual threads provide non-blocking I/O without reactive complexity

**Where Reactor may still be used**:
- Streaming LLM output (token-by-token display)
- Internal WebClient calls (with virtual threads)

---

### Configuration at Construction Time

**Decision**: Loop configuration is provided at construction time via builder pattern, not at execution time.

**Status**: Implemented (2025-12-21)

**Rationale**:
- Follows mini-swe-agent pattern
- Configuration is a construction concern, not an execution concern
- Simplifies `execute()` signature: `execute(userMessage, chatClient, tools)`
- Jury adapter created once in constructor

---

## Generics and Builders

### Pattern 3: Bounded Type Parameters

**Document**: [research/generics-and-builders-analysis.md](research/generics-and-builders-analysis.md)

**Summary**: Research on when to use generics vs simpler alternatives. Key finding: use `<R extends LoopResult>` for result types (Pattern 3 from the analysis).

### Records and Builders

**Decision**: Use builders for user-facing config records (8+ fields), static factory methods for framework-created results, direct construction for internal records.

**Status**: Proposed (2025-12)

**Document**: [design/records-and-builders.md](design/records-and-builders.md)

**Summary**:
- Config records (`TurnLimitedConfig`, etc.): Spring-style builders with `builder()`, `toBuilder()`, `apply(Consumer)`
- Result records (`TurnLimitedResult`, etc.): Static factory methods (`success()`, `terminated()`, `failed()`)
- State records (`LoopState`): `initial()` + immutable update methods
- Internal records: Direct construction
- No Lombok - manual builders for simplicity and control

---

## Loop Patterns

### Pattern Taxonomy

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

---

## Directory Structure

```
plans/
├── DESIGN.md                 # This file - design decision index
├── VISION.md                 # North star - goals, patterns, success criteria
├── PLAN.md                   # Step-by-step implementation plan
├── archive/                  # Completed/superseded documents
├── design/                   # Detailed design documents
│   ├── agentloop-result-types.md
│   └── records-and-builders.md
├── learnings/                # Post-implementation learnings
│   └── STEP-2-SPRING-AI-DIRECT.md
├── research/                 # Research and analysis
│   └── generics-and-builders-analysis.md
└── supporting_repos/         # Reference implementations
    └── mini-swe-agent/
```

---

*Last updated: 2025-12-21*
