# Learning: OpenAGI Pre-Planned Workflow vs GOAP

**Date**: 2026-01-13
**Context**: Clarifying why Cognitive Pipeline (OpenAGI-style) is NOT the same as GOAP

---

## Summary

Both involve "planning before execution," but they are fundamentally different paradigms:

| Aspect | GOAP (Game AI) | OpenAGI / Cognitive Pipeline |
|--------|----------------|------------------------------|
| **Who plans?** | A* algorithm | LLM |
| **Plan representation** | Symbolic action sequence | Natural language steps |
| **Preconditions** | Boolean predicates (hardcoded) | Implicit in LLM reasoning |
| **Effects** | Deterministic, known in advance | Probabilistic, measured empirically |
| **Validation** | Check boolean state after action | Run tests, inspect output |
| **Replanning trigger** | Precondition fails (world changed) | Judge says step failed |

---

## The Core Difference

### GOAP assumes a closed world with known effects

```
Action: WriteFile("config.java")
Precondition: hasPermission(dir) ∧ ¬fileExists("config.java")
Effect: fileExists("config.java") ← TRUE
```

The planner *reasons symbolically* about what will happen. This works in games where shooting a gun always damages the target. The A* algorithm searches through possible action sequences to find one that achieves the goal state.

### Cognitive Pipeline assumes an open world with uncertain effects

```
Step: "Create OAuth configuration class"
Execute: LLM generates code, writes file
Evaluate: Does it compile? Do tests pass? Does Judge approve?
```

The system *measures empirically* what actually happened. The LLM cannot know in advance if its generated code will work. Success is determined by running the code, not by symbolic inference.

---

## Why This Matters for LLM Agents

GOAP requires you to **enumerate all possible states and effects**. For SDLC tasks, this is impossible:

| Task | Why GOAP Fails |
|------|----------------|
| "Fix the bug" | Effect unknown until you try |
| "Refactor for clarity" | What counts as "clear"? |
| "Add OAuth support" | Depends on existing code structure |
| "Optimize performance" | Many valid approaches, results vary |

OpenAGI/Cognitive Pipeline says: **let the LLM plan, then validate outcomes**. The plan is a hypothesis; execution is the experiment.

---

## In Code

```java
// GOAP: Symbolic reasoning (game AI)
// Planner assumes effects are deterministic
if (action.checkPreconditions(worldState)) {
    worldState = action.applyEffects(worldState);  // Assumed correct
}

// Cognitive Pipeline: Empirical validation (LLM agents)
// Effects are measured, not assumed
PlanStep step = llm.plan(task);
Result result = executor.execute(step);
if (!judge.evaluate(result)) {
    plan = llm.replan(task, result.feedback());  // Learn from failure
}
```

---

## What Production Agents Actually Do

The Cognitive Pipeline pattern is what Claude Code, Cursor, and Aider actually use:

1. **PLAN**: LLM decomposes task into steps (natural language)
2. **EXECUTE**: Run each step using tools (TurnLimitedLoop internally)
3. **EVALUATE**: Judge validates success (tests pass? code compiles?)
4. **ADAPT**: On failure, LLM revises plan based on what was learned

They don't pre-compute effects—they try things and check if they worked.

---

## Architectural Implication

This is why agent-harness implements `CognitivePipelineLoop` (Step 7) instead of GOAP:

```
┌─────────────────────────────────────────────────────────────────┐
│                    COGNITIVE PIPELINE                            │
├─────────────────────────────────────────────────────────────────┤
│  1. PLAN (LLM decomposes task into steps)                       │
│     └── "Step 1: Create config, Step 2: Implement provider..."  │
├─────────────────────────────────────────────────────────────────┤
│  2. EXECUTE (per step, using TurnLimitedLoop internally)        │
│     └── Filter tools per step, generate code, run               │
├─────────────────────────────────────────────────────────────────┤
│  3. EVALUATE (Judge validates step success)                     │
│     └── Tests pass? Expected files created? Quality threshold?  │
├─────────────────────────────────────────────────────────────────┤
│  4. ADAPT (on failure: re-plan or retry step)                   │
│     └── LLM revises plan based on what was learned              │
└─────────────────────────────────────────────────────────────────┘
```

GOAP remains in a separate academic project (`planner-goap`) to demonstrate architectural generality, but is **not recommended** for production LLM agents.

---

## Key Quote

> "In open-ended agent systems, the effect of a step is not known in advance and must be measured, not inferred."

---

*This learning clarifies why "pre-planned workflow" in the taxonomy refers to LLM-planned (Cognitive Pipeline), not algorithmically-planned (GOAP).*
