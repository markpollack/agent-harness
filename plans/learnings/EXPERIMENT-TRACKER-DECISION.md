# Learning: ExperimentTracker Removed from harness-patterns

**Date**: 2026-01-13
**Decision**: Remove `ExperimentTracker` from harness-patterns; tracking belongs at the AgentClient/supervisor level

---

## Context

We implemented `ExperimentTracker` in harness-api and wired it through all harness-patterns (TurnLimitedLoop, EvaluatorOptimizerLoop, StateMachineLoop, etc.) to capture turn-level events.

After reviewing the grand plan architecture and discussing the two-loop model, we determined this was premature.

---

## The Two-Loop Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  OUTER LOOP (Supervisor / AgentClient)                          │
│  - spring-ai-bench drives experiments (cases, suites, sweeps)   │
│  - AgentClient advisors add tracking                            │
│  - tuvium-runtime-core stores Run/Experiment data               │
│  - W&B-style: Experiment → Run → Result → Judge                 │
├─────────────────────────────────────────────────────────────────┤
│  INNER LOOP (harness-patterns)                                  │
│  - TurnLimitedLoop, EvaluatorOptimizerLoop, etc.                │
│  - Pure execution: take input, run turns, return result         │
│  - LoopListener for events (already exists)                     │
│  - SLF4J for debugging                                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Why ExperimentTracker Was Premature

1. **Wrong abstraction level**: The W&B model is `Experiment → Run → Result`. Tracking belongs at the Run level (outer loop), not the turn level (inner loop).

2. **No integration point yet**: tuvium-runtime-core exists but isn't wired to AgentClient. Adding tracking inside harness-patterns creates a parallel path.

3. **"More than one way to do it"**: If AgentClient advisors also track (which they should), we'd have competing mechanisms.

4. **First iteration principle**: We're getting ahead of ourselves. Need a working end-to-end system before optimizing observability.

---

## What Inner-Loop Visibility Do We Need?

We considered four options:

| Approach | Description | Decision |
|----------|-------------|----------|
| **A: Pure black box** | No inner visibility | Too opaque |
| **B: LoopListener** | Events fire, can abort | **Sufficient for now** |
| **C: ExperimentTracker** | Events + data capture | Premature |
| **D: Streaming callbacks** | Rich events + intervention | Over-engineering |

**Key insight**: Claude Code is a "black box" but streams events. Our harness already has `LoopListener` which provides similar visibility without the complexity of data capture.

---

## Mid-Loop Intervention Not Needed (Yet)

We discussed whether we need mid-loop intervention:
- Change prompts based on intermediate results
- Dynamically select tools based on context
- Stop based on intermediate judge scores

**Decision**: Not for first iteration. Post-hoc analysis is sufficient. If we need intervention later, we can add it.

---

## The Clean Architecture

**harness-patterns** (inner loop):
- Pure execution, no tracking
- `LoopListener` for event notification
- SLF4J for debug logging
- Returns `LoopResult` with metrics (turns, tokens, cost)

**AgentClient + Advisors** (outer loop):
- `TrackingAdvisor` sends Run data to tuvium-runtime-core
- `JudgeAdvisor` evaluates results
- Context flows through advisor chain

**spring-ai-bench** (supervisor driver):
- Defines cases, suites, sweeps
- Drives AgentClient for each run
- Compares results across configurations

---

## Files Changed

### Removed
- `harness-api/.../tracking/ExperimentTracker.java`

### Reverted to SLF4J logging
- `harness-patterns/.../TurnLimitedLoop.java`
- `harness-patterns/.../EvaluatorOptimizerLoop.java`
- `harness-patterns/.../StateMachineLoop.java`
- `harness-patterns/.../judge/SpringAiJuryAdapter.java`
- `harness-patterns/.../judge/JuryTerminationStrategy.java`

### Test updates
- `TurnLimitedLoopTest.java` - removed tracker references

---

## Key Quotes (from discussion)

> "GOAP is a baseline, not the control architecture. The production system is experiment-driven search—budgeted runs, judge feedback, and adaptive policies—closer to W&B sweeps than classical planning."

> "The supervisor does not plan over symbolic actions. Instead, it performs experiment-driven search over agent configurations."

> "In open-ended agent systems, the effect of a step is not known in advance and must be measured, not inferred."

---

## Next Steps

1. Complete planner-goap as academic baseline (separate project)
2. Wire AgentClient to tuvium-runtime-core via TrackingAdvisor
3. Use spring-ai-bench as supervisor driver
4. One way to track: outer loop only

---

*This learning captures why we removed ExperimentTracker and the architectural reasoning behind the two-loop model.*
