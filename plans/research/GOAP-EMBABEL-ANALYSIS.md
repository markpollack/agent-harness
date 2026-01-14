# GOAP/Embabel Analysis: Architecture, LLM Integration, and Implementation Strategy

**Date**: 2026-01-13
**Purpose**: Clarify how GOAP relates to LLM calls, assess functional completeness requirements, and recommend implementation approach.

---

## Part 1: The Key Architectural Insight

### Where Does the LLM Fit?

**Critical understanding**: In Embabel, **GOAP is the planner, LLM is just an action executor**.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EMBABEL ARCHITECTURE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                        GOAP PLANNER (A*)                            │   │
│   │                                                                      │   │
│   │  Input:  Current World State (from Blackboard)                      │   │
│   │          Available Actions (with preconditions/effects)             │   │
│   │          Goal (desired end state)                                   │   │
│   │                                                                      │   │
│   │  Output: Ordered sequence of actions to reach goal                  │   │
│   │                                                                      │   │
│   │  NOTE: NO LLM CALLS HERE - pure algorithmic planning                │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                               │
│                              ▼                                               │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                      ACTION EXECUTION                                │   │
│   │                                                                      │   │
│   │  Actions can be:                                                    │   │
│   │  ├── LLM-based: ai.withLlm().createObject(prompt, OutputClass)     │   │
│   │  ├── Code-based: Regular Java/Kotlin code                          │   │
│   │  ├── Tool-based: File operations, HTTP calls, etc.                 │   │
│   │  └── Mixed: Combination of above                                    │   │
│   │                                                                      │   │
│   │  LLM IS CALLED HERE (if the action requires it)                     │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                               │
│                              ▼                                               │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                      BLACKBOARD UPDATE                               │   │
│   │                                                                      │   │
│   │  Action results written to blackboard                               │   │
│   │  World state re-determined                                          │   │
│   │  Loop back to GOAP planner for next action                          │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Comparison: Embabel vs Claude Code/Gemini CLI

| Aspect | Embabel (GOAP) | Claude Code / Gemini CLI |
|--------|----------------|--------------------------|
| **Who decides next action?** | GOAP planner (A* algorithm) | LLM reasoning |
| **How is decision made?** | Preconditions/effects matching | Natural language reasoning |
| **LLM role** | Execute predefined actions | Decide AND execute |
| **Flexibility** | Limited to predefined actions | Emergent, dynamic |
| **Predictability** | High (deterministic planning) | Lower (LLM non-determinism) |

**The fundamental tradeoff**:
- **Embabel**: GOAP provides structured, predictable planning but limits flexibility to predefined actions
- **Claude Code**: LLM provides flexible, emergent behavior but with less predictability

---

## Part 2: Is GOAP the Right Approach for SDLC Agents?

### What GOAP Gives You

1. **Predictable action sequences**: Given same state and goal, same plan
2. **Optimal paths**: A* finds lowest-cost action sequence
3. **Explicit preconditions**: Clear about when actions can execute
4. **Declarative goals**: Specify what, not how

### What GOAP Takes Away

1. **Emergent reasoning**: LLM can't "figure out" novel approaches
2. **Dynamic tool selection**: Must predefine all possible actions
3. **Natural language understanding**: Goals must be formalized as conditions
4. **Flexibility**: Can't handle situations not covered by predefined actions

### The SDLC Reality

For an SDLC agent (fixing bugs, writing features, reviewing PRs):

| Task | GOAP Approach | Turn-Limited LLM Approach |
|------|---------------|---------------------------|
| "Fix the null pointer" | Requires predefined "FixNullPointer" action with preconditions | LLM reads error, reasons about cause, writes fix |
| "Add user auth" | Requires decomposed actions: CreateModel, AddController, WriteTests... | LLM explores codebase, decides what to create |
| "Review this PR" | Requires "ReviewFile" action for each file type | LLM reads diff, applies judgment |

**The problem**: SDLC tasks are too varied and context-dependent for predefined GOAP actions to be practical. You'd need thousands of actions to cover real-world scenarios.

**What actually works**: Let the LLM reason about what to do, give it tools (file read/write, run tests, git), and use a simple turn-limited loop.

---

## Part 3: Functional Completeness Assessment

### What Does "Functionally Complete" to Embabel Mean?

Embabel provides:

1. **GOAP Planner** (A* with preconditions/effects)
2. **Action Definition** (preconditions, effects, cost, execution)
3. **World State** (blackboard with facts)
4. **Goal Specification** (target conditions)
5. **OODA Loop** (Observe-Orient-Decide-Act cycle)
6. **Replanning** (replan after each action if world changed)
7. **LLM Integration** (actions can call LLMs via `ai.withLlm()`)

### Minimal Implementation for Functional Parity

```java
// 1. PlanAction - defines an action with preconditions/effects
public interface PlanAction<S> {
    String name();
    boolean checkPreconditions(S state);      // Can this action run?
    S applyEffects(S state);                   // What changes?
    double cost();                             // How expensive?

    // Optional: actual execution (may involve LLM)
    default void execute(ActionContext ctx) {
        // Default: just apply effects symbolically
    }
}

// 2. Goal - desired end state
public interface Goal<S> {
    boolean isSatisfied(S state);
}

// 3. AStarPlanner - finds action sequence
public class AStarPlanner<S> {
    public Optional<List<PlanAction<S>>> plan(
        S initialState,
        Goal<S> goal,
        List<PlanAction<S>> actions
    ) {
        // Standard A* implementation
        // ~50-80 lines of code
    }
}

// 4. GoapLoop - the execution loop with replanning
public class GoapLoop<S> {

    public S execute(S initialState, Goal<S> goal, List<PlanAction<S>> actions) {
        S state = initialState;
        int maxReplans = 10;

        for (int replan = 0; replan < maxReplans; replan++) {
            // Plan
            Optional<List<PlanAction<S>>> plan = planner.plan(state, goal, actions);
            if (plan.isEmpty()) {
                throw new NoPlanFoundException();
            }

            // Execute plan
            for (PlanAction<S> action : plan.get()) {
                // Check preconditions (may have changed)
                if (!action.checkPreconditions(state)) {
                    break; // Replan needed
                }

                // Execute action
                action.execute(context);
                state = action.applyEffects(state);

                // Check if goal achieved early
                if (goal.isSatisfied(state)) {
                    return state;
                }
            }

            // If we completed the plan, check goal
            if (goal.isSatisfied(state)) {
                return state;
            }
            // Otherwise, loop to replan
        }

        throw new MaxReplansExceededException();
    }
}
```

### What This Gives You (Functional Parity)

| Embabel Feature | Our Implementation | Status |
|-----------------|-------------------|--------|
| A* Planning | `AStarPlanner` | ✓ Equivalent |
| Action Preconditions | `PlanAction.checkPreconditions()` | ✓ Equivalent |
| Action Effects | `PlanAction.applyEffects()` | ✓ Equivalent |
| Action Cost | `PlanAction.cost()` | ✓ Equivalent |
| Goal Checking | `Goal.isSatisfied()` | ✓ Equivalent |
| OODA Loop | `GoapLoop.execute()` | ✓ Equivalent |
| Replanning | Loop in `GoapLoop` | ✓ Equivalent |
| LLM Integration | `PlanAction.execute()` can call LLM | ✓ Equivalent |
| Blackboard | World state `S` | ✓ Equivalent (simpler) |

### What We Skip (Embabel Extras)

| Embabel Feature | Our Approach | Reason |
|-----------------|--------------|--------|
| `WorldStateDeterminer` | Direct state access | Over-abstraction |
| `PlatformAwareTools` | Not needed | Our Judge handles tools |
| `AgentScope` annotations | Not needed | We use explicit registration |
| Multi-agent coordination | Not implemented | Out of scope |
| DSL for conditions | Not needed | Java lambdas suffice |

---

## Part 4: The Judge-Centric Subsumption

### How GOAP Maps to Judges

```java
// GOAP preconditions → canExecute Judge
Judge canExecute = ctx -> {
    PlanAction<?> action = ctx.currentAction();
    return Judgment.fromCondition(action.checkPreconditions(ctx.state()));
};

// GOAP effects → didSucceed Judge
Judge didSucceed = ctx -> {
    PlanAction<?> action = ctx.currentAction();
    // Verify effects were actually applied
    return Judgment.fromCondition(action.verifyEffects(ctx.oldState(), ctx.newState()));
};

// GOAP goal → isComplete Judge
Judge isComplete = ctx -> {
    Goal<?> goal = ctx.goal();
    return Judgment.fromCondition(goal.isSatisfied(ctx.state()));
};

// A* planner → Strategy
Strategy aStarStrategy = new AStarStrategy(actions);
```

### The Degeneration

GOAP is a **degenerate case** of Judge-centric architecture where:

| Judge-Centric (General) | GOAP (Degenerate) |
|------------------------|-------------------|
| `Judgment(verdict, score, feedback)` | `boolean` only |
| Fuzzy/uncertain conditions | Binary conditions |
| LLM-based evaluation | Deterministic check |
| Dynamic tool selection | Fixed action set |
| Emergent reasoning | Algorithmic planning |

---

## Part 5: Recommendation

### Implementation Effort

| Component | Lines of Code | Time |
|-----------|---------------|------|
| `PlanAction<S>` interface | ~20 | 15 min |
| `Goal<S>` interface | ~10 | 10 min |
| `AStarPlanner<S>` | ~80 | 2-3 hours |
| `GoapLoop<S>` | ~50 | 1 hour |
| Unit tests | ~100 | 2 hours |
| **Total** | **~260** | **~1 day** |

### What This Proves

1. **GOAP is expressible** in our architecture
2. **Judges generalize** GOAP conditions
3. **No architectural changes** needed to support GOAP
4. **Embabel patterns are a subset** of what we can express

### What This Does NOT Prove

1. That GOAP is the right approach for SDLC agents (it's not)
2. That we should use GOAP for Claude Code-style tasks (we shouldn't)
3. That Embabel's complexity is warranted (it isn't)

### Final Recommendation

**Implement minimal GOAP (~1 day)** to prove the subsumption claim, then move on. The real value of agent-harness is in Turn-Limited and Evaluator-Optimizer patterns that mirror what Claude Code and Gemini CLI actually use.

---

## Appendix: Embabel Source References

From exploration of `/tmp/embabel/`:

| Component | File | Lines |
|-----------|------|-------|
| GOAP Planner | `embabel-agent-api/.../plan/goap/astar/AStarGoapPlanner.kt` | ~200 |
| World State | `embabel-agent-api/.../plan/WorldState.kt` | ~50 |
| Agent Process | `embabel-agent-api/.../core/support/SimpleAgentProcess.kt` | 112-136 |
| LLM Operations | `embabel-agent-api/.../core/support/AbstractLlmOperations.kt` | ~300 |
| Action Definition | Via `@Action` annotation | N/A |

---

## Part 6: GOAP Use Cases Beyond SDLC (Where It Actually Helps)

The Embabel framework reveals use cases where GOAP + LLM **does** provide value:

### 1. Travel Planning (Tripper Agent)

**Problem**: Multi-day itinerary creation with interdependent tasks

**Actions**:
- Extract traveler preferences
- Search flights
- Find accommodations (Airbnb)
- Research attractions
- Create day-by-day schedule
- Generate final itinerary

**Why GOAP helps**: Different travelers have different constraints. The planner orchestrates actions in novel orders based on profiles. If a hotel is unavailable, replans automatically.

### 2. Fact-Checking Agent

**Problem**: Verify multiple claims with confidence scoring

**Actions**:
- Extract assertions from content
- Search web for evidence per claim
- Evaluate source trustworthiness
- Score confidence

**Why GOAP helps**: Parallel execution, dynamic adjustment based on source quality, replanning if contradictions found.

### 3. Research Agent (Multi-LLM)

**Problem**: High-quality research through consensus/critique

**Actions**:
- Research with multiple models in parallel
- Critique and score results
- Retry if confidence low
- Merge into final report

**Why GOAP helps**: Automatically decides when to invoke critique loop, parallelizes efficiently.

### When GOAP Planning Provides Value (REVISED)

**Your critique is valid.** My original table was wrong. Let me correct it:

| Scenario | GOAP Value | Turn-Limited + Plan Tool? | Notes |
|----------|------------|--------------------------|-------|
| **Fixed workflow variations** | Medium | **YES** - LLM can reason about paths | LLM with plan/todo tools discovers novel approaches too |
| **State changes mid-execution** | Medium | **YES** - LLM adapts naturally | LLM reasoning handles unexpected states |
| **Minimize LLM calls** | **HIGH** | No | GOAP's ONE true advantage - algorithmic planning is cheap |
| **Deterministic/auditable** | Medium | **YES** - with tuvium-runtime-core | You already have W&B-lite tracking! |
| **Compliance/enterprise** | **HIGH** | Maybe | "LLM doesn't control flow" matters for regulated industries |
| **Open-ended SDLC tasks** | Low | **YES** | Action explosion problem |
| **Simple tool use** | Low | **YES** | Over-engineered |

**Key corrections**:
1. **LLM + plan/todo tools** can discover novel paths - you have todo tool implementations
2. **tuvium-runtime-core** already provides audit trail (Experiment, Run, TrackingEvent, etc.)
3. **GOAP's real value** is: deterministic control flow for compliance-heavy contexts

### Key Insight: Cost Efficiency

GOAP planning is **NOT using LLM** to decide next action. This is cheap:

```
Turn-Limited: LLM call per decision → expensive, opaque
GOAP: A* search per decision → cheap, deterministic, LLM only in action execution
```

---

## Part 7: Existing Java GOAP Libraries

### Evaluation Summary

| Library | License | Maven Central? | GOAP Support | Recommendation |
|---------|---------|----------------|--------------|----------------|
| **[JavaGOAP](https://github.com/ph1387/JavaGOAP)** | MIT | No (build locally) | Full GOAP | Study for patterns |
| **[jgoap](https://github.com/fdefelici/jgoap)** | MIT | No (build locally) | Full GOAP | Study for patterns |
| **[gdx-ai](https://github.com/libgdx/gdx-ai)** | Apache 2.0 | Yes | NO GOAP (only A*/BT) | Use for A* reference |
| **[JavaGOAPDemo](https://github.com/MrSanchez/JavaGOAPDemo)** | MIT | No | Full GOAP + UI | Visual understanding |

### Key Finding: No Maven Central GOAP Library

Neither JavaGOAP nor jgoap are published to Maven Central. Options:

1. **Fork/vendor** - Copy source into our codebase
2. **Git submodule** - Reference as submodule
3. **Write minimal** - Our own ~260 line implementation

### Recommendation: Hybrid Approach

1. **Study JavaGOAP** for interface design patterns
2. **Write our own thin wrapper** that:
   - Uses our existing Judge interfaces natively
   - Implements A* planning (~80 lines)
   - Provides GoapLoop (~50 lines)
   - Is Judge-centric from the start

This gives us:
- No external dependency issues
- Full control over API
- Native Judge integration
- Clean room implementation

---

## Part 8: Revised Implementation Strategy

### What We Actually Need to Build

| Component | Source | Lines |
|-----------|--------|-------|
| `PlanAction<S>` interface | Inspired by JavaGOAP | ~20 |
| `Goal<S>` interface | Native to our Judge system | ~10 |
| `AStarPlanner<S>` | Study JavaGOAP, write clean room | ~80 |
| `GoapLoop<S>` | Our design, uses Judges | ~50 |
| Adapter to existing Judge | New | ~30 |
| **Total new code** | | **~190** |

### What We DON'T Need

- `IWorldStateProvider` abstraction (use `Map<String, Object>`)
- `IPlanExecutor` interface (inline in GoapLoop)
- Multi-agent coordination (out of scope)
- Custom DSL (Java lambdas suffice)

### Revised Todo List

| Task | Status | Notes |
|------|--------|-------|
| Download papers | ✅ Done | See ~/research/papers/goap/ |
| Clone JavaGOAP for study | Pending | Pattern inspiration only |
| Clone gdx-ai for A* | Skip | We don't need pathfinding |
| Write GOAP-FUNDAMENTALS.md | Skip | Covered in this doc |
| Write EMBABEL-PATTERN-ANALYSIS.md | ✅ Done | This document |
| Write GOAP-JUDGE-MAPPING.md | Merge | Into this doc |
| Implement harness-planning module | Pending | ~190 lines |
| Implement GoapLoop | Pending | Part of above |
| Write unit tests | Pending | ~100 lines |
| Write GOAP-VALIDATION.md | Skip | Tests suffice |

### Final Deliverable

A single `harness-planning` module with:
- `PlanAction<S>`, `Goal<S>` interfaces
- `AStarPlanner<S>` implementation
- `GoapLoop<S>` showing Judge subsumption
- Unit tests with classic GOAP example (game AI scenario) + workflow example (travel/fact-check style)

**Total effort**: ~1 day (reduced from original estimate by using existing patterns)

---

## Part 9: Where GOAP/Embabel Fits in Tuvium Architecture (CORRECTED)

### My Previous Understanding Was Wrong

I incorrectly suggested GOAP might fit at the tuvium-agent-supervisor level. After reading the sdk-sync-agent-implementation-plan.md, I now understand your actual vision:

### Your Actual Vision for tuvium-agent-supervisor

From `sdk-sync-agent-implementation-plan.md`:

```
┌─────────────────────────────────────────────────────────────────┐
│                    OUTER LOOP (W&B-Style Sweeps)                │
│  tuvium-runtime-core: Experiment tracking, metrics, comparisons  │
├─────────────────────────────────────────────────────────────────┤
│   Sweep Controls:                                               │
│   • Compare runs across models/providers                        │
│   • Track cost/latency/quality trade-offs                       │
│   • Identify optimal configuration per task type                │
└─────────────────────────────────────────────────────────────────┘
```

**The supervisor is about**:
1. **W&B-style sweeps** - running experiments across models, providers, harness configs
2. **Workforce management** - tracking what agents are doing, status, convergence
3. **Empirical optimization** - find the best config for a task through experimentation
4. **NOT deterministic GOAP planning**

### The Two-Loop Architecture (Your Design)

```
Loop 2: OUTER (Supervisor)
├── Runs sweeps across: models, providers, harnesses, prompts
├── Tracks experiments with tuvium-runtime-core
├── Compares: cost, latency, quality, convergence rate
└── Finds: optimal configuration empirically

Loop 1: INNER (Harness)
├── Turn-limited or Evaluator-Optimizer pattern
├── LLM reasons about what to do (with plan/todo tools)
├── Each iteration: analyze → generate → judge → refine
└── Converges when judge score threshold met
```

### Why Would Anyone Use Embabel? (The Real Question)

Given your architecture already provides:
- **Turn-limited loops** - LLM controls flow, reasons about tasks
- **Plan/todo tools** - LLM discovers novel approaches
- **W&B-style tracking** - Full audit trail (Experiment, Run, TrackingEvent)
- **Sweeps** - Empirical optimization across configurations
- **Judges** - Quality evaluation without GOAP

**What does GOAP add that you don't already have?**

| Capability | Tuvium Approach | GOAP/Embabel Approach |
|------------|-----------------|----------------------|
| Novel path discovery | LLM + plan/todo tools | A* over predefined actions |
| Audit trail | tuvium-runtime-core | World state transitions |
| Configuration optimization | W&B sweeps | Not really - single config |
| Adaptability | LLM reasoning | Replanning (limited) |
| Cost efficiency | Sweep to find cheap model | Non-LLM planner |

**GOAP's ONE remaining advantage**: Non-LLM planning is cheaper than LLM reasoning.

But this is minor because:
- You're already optimizing cost via model sweeps (Haiku vs Opus)
- The inner loop LLM calls are the real cost, not "what to do next" decisions
- Plan/todo tool calls are a tiny fraction of total tokens

### Honest Assessment: Why Use Embabel?

**Legitimate use cases for GOAP/Embabel**:

1. **When LLM reasoning is the liability, not the feature**
   - Compliance contexts where "LLM decided" is unacceptable
   - When you need deterministic, auditable control flow (beyond just logging)
   - When the action space is TRULY enumerable (game AI, simulation)

2. **When cost of "thinking" dominates**
   - Many tiny decisions (game tick every frame)
   - But in SDLC, the "doing" dominates the "thinking"

**NOT legitimate for most SDLC/agent use cases**:
- Action space is too large
- LLM reasoning IS the product value
- W&B tracking provides audit
- Sweeps provide optimization

### Revised Conclusion

**GOAP/Embabel solves a problem you don't have.**

Your architecture (harness + supervisor + runtime-core) already addresses:
- Audit: runtime-core tracking
- Optimization: W&B sweeps
- Novel paths: LLM + plan/todo
- Quality control: Judges

The only scenario where GOAP adds value is when "LLM controls flow" is **politically/legally unacceptable**, not when it's technically inferior.

### Do You Need GOAP at All?

**For academic completeness**: Yes, ~190 lines to prove subsumption.

**For Tuvium products**: Probably not, unless you specifically target regulated industries where "deterministic control flow" is a selling point.

**For agent-harness**: Definitely not in the inner loop.

---

## Part 10: Final Recommendation (REVISED)

### For agent-harness (Inner Loops)

**Keep Turn-Limited and Evaluator-Optimizer as primary patterns.**
- These work for SDLC agents (Claude Code, Gemini CLI pattern)
- LLM reasoning + plan/todo tools handles novel paths
- tuvium-runtime-core handles audit requirements
- **No GOAP needed here**

### For tuvium-agent-supervisor (Outer Loops)

**NOT GOAP.** Your vision is W&B-style experiment orchestration:
- Sweeps across models, providers, harnesses
- Track and compare runs empirically
- Workforce management (what agents are doing, status)
- **This is fundamentally different from GOAP's deterministic planning**

### Implementation Scope (Revised)

| Component | Priority | Rationale |
|-----------|----------|-----------|
| Turn-Limited improvements | **HIGH** | Core pattern, what works |
| Evaluator-Optimizer refinements | **HIGH** | Core pattern |
| W&B-style sweep orchestration | **HIGH** | Your actual supervisor vision |
| GOAP implementation | **LOW** | Academic completeness only |

### The Academic Claim (Preserved)

You can still claim:
> "GOAP is a degenerate case of our Judge-centric architecture where:
> - the generator is symbolic,
> - the judges are boolean,
> - and the strategy is A*."

This is true, academically sound, and requires only ~190 lines to prove.

### Why Embabel Exists (Honest Answer)

Embabel was built by Rod Johnson (Spring Framework creator) who comes from enterprise Java where:
- Explicit, auditable control flow is valued
- "Magic" (LLM reasoning) is viewed with suspicion
- Determinism and reproducibility are paramount

This worldview produces a valid but **narrow** framework for:
- Compliance-heavy enterprise workflows
- Simulations and game AI
- Educational/research environments

**It does NOT fit**:
- Developer productivity tools (where LLM reasoning IS the value)
- SDLC agents (action space explosion)
- Your Tuvium architecture (which is empirical, not deterministic)

### Bottom Line

**GOAP solves a problem you've already solved differently (and arguably better).**

Your approach:
- LLM reasons + judges evaluate = quality
- W&B sweeps = optimization
- runtime-core tracking = audit

Embabel's approach:
- Deterministic planner = control
- Explicit actions = auditability
- No LLM in control flow = compliance

These are **philosophically different**. Neither is wrong, but yours aligns with what production CLI agents (Claude Code, Gemini, Aider) actually do.

---

## Part 11: Test Strategy - Prove It Works, Show It's Not Relevant

### Purpose

Include Embabel-style examples in tests to demonstrate:
1. **GOAP is expressible** in our architecture (subsumption proof)
2. **It works correctly** (not a strawman implementation)
3. **It's not relevant** for SDLC/agent use cases (by showing the contrast)

### Test Suite Structure

```
harness-planning/src/test/java/.../goap/
├── classic/
│   └── GameAiGoapTest.java        # Classic F.E.A.R.-style example
├── embabel/
│   ├── HoroscopeNewsGoapTest.java # Embabel's beginner example
│   ├── FactCheckerGoapTest.java   # Embabel's fact-checking example
│   └── TravelPlannerGoapTest.java # Embabel's Tripper example
└── contrast/
    └── WhyNotForSdlcTest.java     # Shows why GOAP fails for SDLC
```

### Test 1: Classic Game AI (F.E.A.R. Style)

**Purpose**: Prove our A* implementation works correctly on the canonical GOAP use case.

```java
/**
 * Classic GOAP example from game AI (F.E.A.R. style).
 * This is what GOAP was designed for - works well here.
 */
@Test
void enemyAiPlanningExample() {
    // World state: boolean flags
    Map<String, Boolean> initialState = Map.of(
        "hasWeapon", false,
        "weaponLoaded", false,
        "targetVisible", false,
        "targetDead", false
    );

    // Goal: eliminate target
    Goal<Map<String, Boolean>> goal = state ->
        state.getOrDefault("targetDead", false);

    // Actions with preconditions and effects
    List<PlanAction<Map<String, Boolean>>> actions = List.of(
        new PickUpWeapon(),    // effect: hasWeapon = true
        new LoadWeapon(),      // precondition: hasWeapon; effect: weaponLoaded = true
        new FindTarget(),      // effect: targetVisible = true
        new Shoot()            // precondition: weaponLoaded, targetVisible; effect: targetDead = true
    );

    // Plan and execute
    var result = goapLoop.execute(initialState, goal, actions);

    // Verify: planner finds optimal sequence
    assertThat(result.isComplete()).isTrue();
    assertThat(result.plan()).containsExactly(
        "PickUpWeapon", "LoadWeapon", "FindTarget", "Shoot"
    );
}
```

**What this proves**: Our A* planner works correctly on GOAP's home turf.

### Test 2: Embabel Horoscope/News Example

**Purpose**: Replicate Embabel's beginner example to show functional parity.

```java
/**
 * Embabel's "Star News Finder" example.
 * Shows GOAP working for simple workflow orchestration.
 *
 * From: embabel-agent-api/.../example/simple/horoscope/
 */
@Test
void horoscopeNewsFinderExample() {
    // World state
    Map<String, Object> initialState = Map.of(
        "hasPersonName", false,
        "hasStarSign", false,
        "hasHoroscope", false,
        "hasNews", false,
        "hasSynthesis", false
    );

    // Goal: produce synthesis
    Goal<Map<String, Object>> goal = state ->
        (Boolean) state.getOrDefault("hasSynthesis", false);

    // Actions (in Embabel these would call LLMs internally)
    List<PlanAction<Map<String, Object>>> actions = List.of(
        new ExtractPersonAndSign(),  // effect: hasPersonName, hasStarSign
        new FetchHoroscope(),        // precondition: hasStarSign; effect: hasHoroscope
        new SearchRelevantNews(),    // precondition: hasPersonName; effect: hasNews
        new WriteSynthesis()         // precondition: hasHoroscope, hasNews; effect: hasSynthesis
    );

    var result = goapLoop.execute(initialState, goal, actions);

    assertThat(result.isComplete()).isTrue();
    // Planner finds valid sequence (order may vary for parallel-capable actions)
}
```

**What this proves**: Embabel-style workflows are expressible in our architecture.

### Test 3: Embabel Fact-Checker Example

**Purpose**: Show GOAP working for parallel verification workflows.

```java
/**
 * Embabel's fact-checking agent example.
 * Multiple claims verified in parallel, then merged.
 *
 * From: embabel-agent-api/.../example/dogfood/factchecker/
 */
@Test
void factCheckerExample() {
    Map<String, Object> initialState = Map.of(
        "hasContent", true,
        "claimsExtracted", false,
        "claimsVerified", false,
        "confidenceScored", false,
        "reportGenerated", false
    );

    Goal<Map<String, Object>> goal = state ->
        (Boolean) state.getOrDefault("reportGenerated", false);

    List<PlanAction<Map<String, Object>>> actions = List.of(
        new ExtractClaims(),       // effect: claimsExtracted
        new VerifyClaimsParallel(),// precondition: claimsExtracted; effect: claimsVerified
        new ScoreConfidence(),     // precondition: claimsVerified; effect: confidenceScored
        new GenerateReport()       // precondition: confidenceScored; effect: reportGenerated
    );

    var result = goapLoop.execute(initialState, goal, actions);

    assertThat(result.isComplete()).isTrue();
}
```

### Test 4: Embabel Travel Planner (Tripper) Example

**Purpose**: Show GOAP working for complex multi-step workflows.

```java
/**
 * Embabel's Tripper travel planning example.
 * Complex workflow with dependencies between steps.
 *
 * From: github.com/embabel/tripper
 */
@Test
void travelPlannerExample() {
    Map<String, Object> initialState = Map.of(
        "hasPreferences", false,
        "hasFlights", false,
        "hasAccommodation", false,
        "hasAttractions", false,
        "hasItinerary", false
    );

    Goal<Map<String, Object>> goal = state ->
        (Boolean) state.getOrDefault("hasItinerary", false);

    List<PlanAction<Map<String, Object>>> actions = List.of(
        new ExtractPreferences(),    // effect: hasPreferences
        new SearchFlights(),         // precondition: hasPreferences; effect: hasFlights
        new FindAccommodation(),     // precondition: hasPreferences; effect: hasAccommodation
        new ResearchAttractions(),   // precondition: hasPreferences; effect: hasAttractions
        new GenerateItinerary()      // precondition: hasFlights, hasAccommodation, hasAttractions
    );

    var result = goapLoop.execute(initialState, goal, actions);

    assertThat(result.isComplete()).isTrue();
}
```

### Test 5: Why GOAP Fails for SDLC (The Contrast)

**Purpose**: Demonstrate why GOAP is NOT suitable for SDLC agent tasks.

```java
/**
 * Demonstrates why GOAP doesn't work for SDLC tasks.
 * The action space explodes, making predefined actions impractical.
 */
@Test
void whyGoapFailsForSdlcTasks() {
    // Task: "Fix the null pointer exception in UserService.java"

    // PROBLEM 1: How do you predefine actions for this?
    // You'd need actions like:
    List<PlanAction<?>> impossibleActions = List.of(
        // new FixNullPointerInUserService(),  // Too specific
        // new FixNullPointer(),               // How? Where? What null?
        // new AddNullCheck(),                 // To which variable? Which line?
        // new InitializeField(),              // Which field?
        // new AddOptional(),                  // Where?
        // ... thousands more variants
    );

    // PROBLEM 2: Effects are not boolean
    // "Did fixing work?" requires:
    // - Compile the code
    // - Run tests
    // - Evaluate if the fix is correct
    // - Judge if there are side effects
    // These are EVALUATIVE, not assertive.

    // PROBLEM 3: State is not enumerable
    // "Current state" = entire codebase + error message + stack trace + context
    // Cannot be reduced to Map<String, Boolean>

    // CONTRAST: Turn-Limited Loop handles this naturally
    // LLM reads error → reasons about cause → proposes fix → judge evaluates
    // No predefined action set needed.

    // This test intentionally has no assertions - it's documentation
    // showing WHY we don't use GOAP for SDLC tasks.
}

/**
 * Shows what SDLC tasks actually need: LLM reasoning, not GOAP planning.
 */
@Test
void whatSdlcTasksActuallyNeed() {
    // The same "fix null pointer" task with Turn-Limited:

    // 1. LLM receives: error message, stack trace, relevant code
    // 2. LLM reasons: "The null comes from uninitialized field X"
    // 3. LLM generates: code patch to initialize X
    // 4. Judge evaluates: does it compile? do tests pass?
    // 5. If not, refine and retry

    // This is EMERGENT action selection, not predefined.
    // The LLM decides what to do based on context.
    // This is why Claude Code, Gemini CLI, Aider all use Turn-Limited.

    var turnLimitedLoop = new TurnLimitedLoop(/* ... */);
    // turnLimitedLoop.execute(task);  // This is what works.
}
```

### Summary: What the Tests Prove

| Test | Proves | Conclusion |
|------|--------|------------|
| Game AI (F.E.A.R.) | A* works correctly | GOAP's home turf - works well |
| Horoscope/News | Embabel example works | Functional parity achieved |
| Fact-Checker | Parallel workflows work | GOAP can orchestrate verification |
| Travel Planner | Complex dependencies work | GOAP handles multi-step workflows |
| **SDLC Contrast** | GOAP fails for SDLC | Action explosion, non-boolean effects |

### Documentation Comment for the Module

```java
/**
 * GOAP (Goal-Oriented Action Planning) implementation.
 *
 * <h2>Purpose</h2>
 * This module demonstrates that GOAP is a degenerate case of our
 * Judge-centric architecture:
 * <ul>
 *   <li>Preconditions → canExecute() Judge returning boolean</li>
 *   <li>Effects → didSucceed() Judge returning boolean</li>
 *   <li>Goals → isComplete() Judge returning boolean</li>
 *   <li>A* Planner → Strategy implementation</li>
 * </ul>
 *
 * <h2>When to Use GOAP</h2>
 * GOAP is appropriate when:
 * <ul>
 *   <li>Action space is finite and enumerable</li>
 *   <li>Effects are deterministic and boolean</li>
 *   <li>World state can be discretized</li>
 *   <li>Deterministic control flow is required (compliance)</li>
 * </ul>
 *
 * <h2>When NOT to Use GOAP</h2>
 * GOAP is NOT appropriate for SDLC/coding agents because:
 * <ul>
 *   <li>Action space explodes (infinite possible fixes)</li>
 *   <li>Effects are evaluative, not boolean (did the fix work?)</li>
 *   <li>LLM reasoning IS the value, not a liability</li>
 * </ul>
 *
 * For SDLC tasks, use {@link TurnLimitedLoop} or {@link EvaluatorOptimizerLoop}.
 *
 * <h2>See Also</h2>
 * <ul>
 *   <li>plans/research/GOAP-EMBABEL-ANALYSIS.md - Full analysis</li>
 *   <li>AGENT-LOOP-TAXONOMY.md - Pattern comparison</li>
 * </ul>
 *
 * @see TurnLimitedLoop
 * @see EvaluatorOptimizerLoop
 */
package org.springaicommunity.agents.harness.patterns.goap;
```

---

*Last Updated: 2026-01-13*
