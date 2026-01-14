# Learning: Two-Axis Taxonomy Model

**Date**: 2026-01-14
**Context**: Extending the 8-pattern taxonomy with an orthogonal execution definition dimension

---

## The Problem with "Pattern 9"

When analyzing Koog, LangGraph, and LlamaIndex, we initially considered adding "Graph-Based" as Pattern 9. This was wrong.

**Why it's wrong**: Graph-defined execution is not a *termination pattern*—it's a *definition model*. A graph-defined system still uses termination patterns (reach terminal node = finish tool pattern, max iterations = turn-limited pattern).

---

## The Two-Axis Model

### Axis 1: Termination Pattern (How the loop ends)

Our existing 8 patterns:

| # | Pattern | Termination Trigger |
|---|---------|---------------------|
| 1 | Turn-Limited Multi-Condition | max_turns OR timeout OR abort OR finish |
| 2 | Finish Tool Detection | Agent calls specific tool |
| 3 | Status-Based State Machine | Reach terminal state |
| 4 | Pre-Planned Workflow | All steps complete |
| 5 | Generator/Yield | Caller stops iterating |
| 6 | Exception-Driven | Error propagates |
| 7 | Event-Driven Single-Step | No event to handle |
| 8 | Polling with Sleep | Condition flag set |

### Axis 2: Execution Definition Model (How the loop is defined)

| Model | Definition Style | Runtime Behavior | Examples |
|-------|-----------------|------------------|----------|
| **Imperative** | Inline code (while/for) | Direct execution | Gemini CLI, Aider, Claude Code, Swarm |
| **Generator** | Yield points | Caller-controlled iteration | Auto-Code-Rover, Python async |
| **Graph-Defined** | Nodes + edges | Graph traversal loop | Koog, LangGraph |
| **Event-Driven** | Event handlers | Dispatch loop | MetaGPT |

---

## The Matrix

Each cell represents a valid combination:

```
                         EXECUTION DEFINITION MODEL
                    ┌───────────┬───────────┬─────────────┬───────────┐
                    │Imperative │ Generator │Graph-Defined│Event-Driven│
────────────────────┼───────────┼───────────┼─────────────┼───────────┤
Turn-Limited        │ Gemini    │           │ Koog node   │           │
                    │ Swarm     │           │ (max iter)  │           │
────────────────────┼───────────┼───────────┼─────────────┼───────────┤
Finish Tool         │ Aider     │           │ LangGraph   │           │
                    │           │           │ (END node)  │           │
────────────────────┼───────────┼───────────┼─────────────┼───────────┤
State Machine       │ OpenHands │           │ Koog        │           │
                    │ Embabel   │           │ (subgraph)  │           │
────────────────────┼───────────┼───────────┼─────────────┼───────────┤
Pre-Planned         │ OpenAGI   │           │ Koog        │           │
Workflow            │           │           │ strategy    │           │
────────────────────┼───────────┼───────────┼─────────────┼───────────┤
Generator/Yield     │           │ Auto-Code │ LangGraph   │           │
                    │           │ Rover     │ runner      │           │
────────────────────┼───────────┼───────────┼─────────────┼───────────┤
Exception-Driven    │ Aider     │           │             │           │
────────────────────┼───────────┼───────────┼─────────────┼───────────┤
Event-Driven        │           │           │             │ MetaGPT   │
────────────────────┼───────────┼───────────┼─────────────┼───────────┤
Polling             │ Devika    │           │             │           │
                    │ Devon     │           │             │           │
────────────────────┴───────────┴───────────┴─────────────┴───────────┘
```

---

## Key Insight: Composability

Graph-defined systems **host** the other patterns. A Koog graph can have:
- A node that internally runs a turn-limited loop
- A node that uses evaluator-optimizer pattern
- Edge conditions that implement state machine transitions

```
┌─────────────────────────────────────────────────────────────────┐
│  KOOG GRAPH                                                      │
│  ┌──────────┐    ┌──────────────────────┐    ┌──────────┐      │
│  │  Start   │───>│  CodingNode          │───>│  Finish  │      │
│  │          │    │  ┌──────────────────┐│    │          │      │
│  │          │    │  │ TurnLimitedLoop  ││    │          │      │
│  │          │    │  │ (agent-harness)  ││    │          │      │
│  │          │    │  └──────────────────┘│    │          │      │
│  └──────────┘    └──────────────────────┘    └──────────┘      │
└─────────────────────────────────────────────────────────────────┘
```

---

## What This Means for agent-harness

### Current Position

agent-harness is **Imperative** on the definition axis, supporting multiple termination patterns:
- TurnLimitedLoop (Pattern 1)
- EvaluatorOptimizerLoop (Pattern 2 characteristics)
- StateMachineLoop (Pattern 3)

### Future Options

| Option | Description | When |
|--------|-------------|------|
| **Stay Imperative** | Focus on termination patterns | Now |
| **Support Generator** | Add yield-based loops | If needed |
| **Bridge to Graph** | Our loops as graph nodes | If Koog/LangGraph adoption grows |
| **Native Graph** | Add graph DSL to agent-harness | Probably never (use Koog instead) |

**Recommendation**: Stay imperative, ensure our loops can be easily wrapped as graph nodes.

---

## Clarification: What's NOT in This Model

### GOAP (Goal-Oriented Action Planning)

GOAP is an **algorithm**, not an execution model. It computes plans via A* search over boolean state spaces. We excluded it because:
- Effects must be deterministic (LLM outputs are not)
- State space must be enumerable (codebases are infinite)
- It's a planning algorithm, not a loop definition model

See: `learnings/OPENAGI-VS-GOAP.md`

### Workflow-Scripted

Initially considered, but it's not a distinct model:
- If steps are hardcoded → **Imperative** with pre-planned termination
- If steps are in config → **Imperative** with config-driven iteration
- If steps are nodes → **Graph-Defined**

---

## Summary

| Question | Answer |
|----------|--------|
| Is graph-based Pattern 9? | **No** - it's an orthogonal dimension |
| Does agent-harness need graphs? | **No** - our loops can be graph nodes |
| Should we track this dimension? | **Yes** - helps classify frameworks |
| What's our focus? | **Termination patterns** in imperative style |

---

*This model cleanly separates "how loops end" (termination patterns) from "how loops are defined" (execution models), avoiding taxonomy inflation while acknowledging architectural diversity.*
