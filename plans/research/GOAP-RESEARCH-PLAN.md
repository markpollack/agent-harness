# Research Plan: GOAP as Judge-Centric Special Case

**Purpose**: Provide rigorous evidence that GOAP (Goal-Oriented Action Planning) is a degenerate case of the Judge-centric AgentLoop architecture, with a clean-room Java implementation.

**Date**: 2026-01-13

**Audience**: Java AI community, Embabel maintainers, academic reviewers

---

## Executive Summary

To claim that "GOAP is a special case" we must:

1. **Formally map** GOAP concepts to Judge-centric primitives
2. **Implement** a clean-room GOAP planner using only our interfaces
3. **Demonstrate** that Embabel-style workflows can be expressed in our framework
4. **Prove** no loss of expressiveness (anything GOAP can do, Judges can do)

---

## Part 1: GOAP Fundamentals Research

### 1.1 Academic Foundation

**Primary sources to study**:

| Source | Key Contribution |
|--------|------------------|
| Orkin (2003) - "Applying Goal-Oriented Action Planning to Games" | Original GOAP paper, F.E.A.R. AI |
| Orkin (2006) - "Three States and a Plan" | Simplified state machine + GOAP hybrid |
| Russell & Norvig - "AI: A Modern Approach" Ch. 10-11 | STRIPS planning, A* search |
| Bylund (2017) - "Goal-Oriented Action Planning in Hitman" | Modern game AI GOAP |

**Key concepts to document**:

1. **World State**: Set of boolean propositions (e.g., `{hasWeapon: true, targetVisible: false}`)
2. **Action**: `(name, preconditions, effects, cost)`
3. **Goal**: Desired world state subset
4. **Planner**: A* search through action space, backward from goal
5. **Plan**: Ordered sequence of actions to reach goal

### 1.2 GOAP Algorithm Pseudocode

```
function GOAP_Plan(initialState, goalState, actions):
    openSet = PriorityQueue()  // Ordered by f(n) = g(n) + h(n)
    openSet.add(Node(state=goalState, g=0, parent=null))

    while openSet not empty:
        current = openSet.pop()

        if current.state ⊆ initialState:
            return reconstructPlan(current)

        for action in actions:
            if action.effects ∩ current.state ≠ ∅:  // Action is relevant
                newState = (current.state - action.effects) ∪ action.preconditions
                newNode = Node(
                    state = newState,
                    g = current.g + action.cost,
                    h = heuristic(newState, initialState),
                    parent = current,
                    action = action
                )
                openSet.add(newNode)

    return null  // No plan found
```

### 1.3 Research Deliverable

**Document**: `research/GOAP-FUNDAMENTALS.md`
- Complete GOAP algorithm documentation
- Comparison with STRIPS, HTN planning
- Complexity analysis
- Known limitations (combinatorial explosion, etc.)

---

## Part 2: Embabel Architecture Analysis

### 2.1 Code Study (Read-Only, No Copying)

**Files to analyze** (from taxonomy evidence):
- `embabel-agent-api/.../AbstractAgentProcess.kt`
- `embabel-agent-api/.../AgentProcessStatusCode.kt`
- Any GOAP-related planning code

**Questions to answer**:

1. How does Embabel represent world state?
2. How are actions defined (preconditions, effects)?
3. What planning algorithm is used (A*, forward chaining, other)?
4. How does the state machine interact with the planner?
5. What termination conditions exist?

### 2.2 Pattern Extraction

Extract the **abstract patterns** without copying implementation:

| Embabel Concept | Abstract Pattern | Our Equivalent |
|-----------------|------------------|----------------|
| `AgentProcessStatusCode` | Status enum | `LoopStatus` |
| `tick()` | Single step execution | `generator.generate()` |
| `identifyEarlyTermination()` | Termination check | `isComplete()` Judge |
| World state updates | State mutation | `JudgmentContext.withState()` |
| Action preconditions | Guard conditions | `canExecute()` Judge |

### 2.3 Research Deliverable

**Document**: `research/EMBABEL-PATTERN-ANALYSIS.md`
- Abstract patterns extracted (no code copying)
- Mapping to our taxonomy
- Gaps identified
- Clean-room requirements

---

## Part 3: Formal Mapping

### 3.1 The Subsumption Theorem

**Claim**: For any GOAP system G with actions A, world state W, and goal state g:
- There exists a Judge-centric AgentLoop L such that
- L produces the same action sequences as G
- L terminates iff G terminates
- L has equivalent expressiveness

**Proof sketch**:

1. **World State ↔ JudgmentContext.state()**
   - Both are key-value maps
   - Both support immutable updates
   - Isomorphic representation

2. **Precondition ↔ canExecute() Judge**
   ```java
   // GOAP precondition
   action.preconditions = {hasWeapon: true, nearTarget: true}

   // Judge equivalent
   Judge canExecute = ctx -> {
       Map<String, Object> state = ctx.state();
       boolean hasWeapon = (Boolean) state.get("hasWeapon");
       boolean nearTarget = (Boolean) state.get("nearTarget");
       return Judgment.fromCondition(hasWeapon && nearTarget);
   };
   ```

3. **Effect ↔ didSucceed() Judge + State Update**
   ```java
   // GOAP effect
   action.effects = {targetEliminated: true}

   // Judge equivalent
   Judge didSucceed = ctx -> {
       // Verify effect applied
       boolean eliminated = (Boolean) ctx.state().get("targetEliminated");
       return Judgment.fromCondition(eliminated);
   };
   ```

4. **Goal ↔ isComplete() Judge**
   ```java
   // GOAP goal
   goal = {missionComplete: true, agentSafe: true}

   // Judge equivalent
   Judge isComplete = Judges.fromConditions(Map.of(
       "missionComplete", true,
       "agentSafe", true
   ));
   ```

5. **A* Planner ↔ Strategy**
   ```java
   // GOAP A* planner becomes a Strategy implementation
   Strategy aStarStrategy = new AStarStrategy(actions, heuristic);
   ```

### 3.2 Key Insight: Judges Generalize Boolean Conditions

GOAP uses **boolean conditions**:
- Preconditions: `Map<String, Boolean>`
- Effects: `Map<String, Boolean>`
- Goals: `Map<String, Boolean>`

Judges use **scored/uncertain/feedback-rich evaluations**:
- `Judgment(verdict, score, reasoning, feedback, signal)`

**Generalization**: `Judgment.fromCondition(boolean)` degenerates to GOAP semantics.

### 3.3 Research Deliverable

**Document**: `research/GOAP-JUDGE-MAPPING.md`
- Formal mapping with proofs
- Code examples for each mapping
- Edge cases and limitations
- Expressiveness comparison

---

## Part 4: Clean-Room Implementation

### 4.1 Core Interfaces (New)

```java
// harness-planning module

/**
 * A GOAP-style action with preconditions, effects, and cost.
 * Clean-room design inspired by academic GOAP literature.
 */
public interface PlanAction<S> {

    String name();

    /**
     * Can this action execute in the given state?
     * Maps to: Judges.canExecute()
     */
    Judgment checkPreconditions(S state, JudgmentContext context);

    /**
     * Execute the action and return new state.
     * Maps to: Generator.generate()
     */
    S execute(S state, JudgmentContext context);

    /**
     * Did the action achieve its intended effects?
     * Maps to: Judges.didSucceed()
     */
    Judgment verifyEffects(S oldState, S newState, JudgmentContext context);

    /**
     * Cost of this action (for A* heuristic).
     * Maps to: Strategy.shouldExecute() scoring
     */
    double cost(S state);
}

/**
 * A goal is simply a Judge that returns PASS when satisfied.
 */
public interface Goal<S> extends Judge {

    /**
     * Is the goal satisfied in this state?
     */
    Judgment isSatisfied(S state, JudgmentContext context);

    @Override
    default Judgment judge(JudgmentContext context) {
        @SuppressWarnings("unchecked")
        S state = (S) context.state().get("worldState");
        return isSatisfied(state, context);
    }
}

/**
 * Planner that finds action sequences to reach goals.
 * A* implementation as Strategy.
 */
public interface Planner<S> extends Strategy {

    /**
     * Find a plan (action sequence) from current state to goal.
     */
    Optional<Plan<S>> plan(S initialState, Goal<S> goal, List<PlanAction<S>> actions);
}

/**
 * A sequence of actions forming a plan.
 */
public record Plan<S>(
    List<PlanAction<S>> actions,
    double totalCost,
    List<S> intermediateStates  // For debugging/visualization
) {
    public boolean isEmpty() {
        return actions.isEmpty();
    }

    public PlanAction<S> nextAction() {
        return actions.isEmpty() ? null : actions.get(0);
    }

    public Plan<S> advance() {
        return new Plan<>(
            actions.subList(1, actions.size()),
            totalCost - actions.get(0).cost(intermediateStates.get(0)),
            intermediateStates.subList(1, intermediateStates.size())
        );
    }
}
```

### 4.2 A* Planner Implementation

```java
/**
 * Clean-room A* planner for GOAP-style planning.
 * Based on academic literature, not copied from any existing implementation.
 */
public class AStarPlanner<S> implements Planner<S> {

    private final Function<S, Double> heuristic;  // Estimated cost to goal
    private final int maxNodes;                    // Search limit

    @Override
    public Optional<Plan<S>> plan(S initialState, Goal<S> goal,
                                   List<PlanAction<S>> actions) {

        // A* search from initial state toward goal
        PriorityQueue<SearchNode<S>> openSet = new PriorityQueue<>(
            Comparator.comparingDouble(SearchNode::f)
        );
        Set<S> closedSet = new HashSet<>();

        openSet.add(new SearchNode<>(initialState, 0, heuristic.apply(initialState),
                                      null, null));

        int nodesExplored = 0;
        while (!openSet.isEmpty() && nodesExplored < maxNodes) {
            SearchNode<S> current = openSet.poll();
            nodesExplored++;

            // Goal check
            if (goal.isSatisfied(current.state(), JudgmentContext.of(current.state()))
                    .pass()) {
                return Optional.of(reconstructPlan(current));
            }

            closedSet.add(current.state());

            // Expand successors
            for (PlanAction<S> action : actions) {
                JudgmentContext ctx = JudgmentContext.of(current.state());

                // Check preconditions (canExecute)
                if (!action.checkPreconditions(current.state(), ctx).pass()) {
                    continue;
                }

                // Execute action
                S newState = action.execute(current.state(), ctx);

                if (closedSet.contains(newState)) {
                    continue;
                }

                double g = current.g() + action.cost(current.state());
                double h = heuristic.apply(newState);

                openSet.add(new SearchNode<>(newState, g, h, current, action));
            }
        }

        return Optional.empty();  // No plan found
    }

    private Plan<S> reconstructPlan(SearchNode<S> goalNode) {
        List<PlanAction<S>> actions = new ArrayList<>();
        List<S> states = new ArrayList<>();
        double totalCost = goalNode.g();

        SearchNode<S> current = goalNode;
        while (current.parent() != null) {
            actions.add(0, current.action());
            states.add(0, current.state());
            current = current.parent();
        }
        states.add(0, current.state());  // Initial state

        return new Plan<>(actions, totalCost, states);
    }

    private record SearchNode<S>(
        S state,
        double g,      // Cost from start
        double h,      // Heuristic to goal
        SearchNode<S> parent,
        PlanAction<S> action
    ) {
        double f() { return g + h; }
    }
}
```

### 4.3 GOAP Loop (Unified with AgentLoop)

```java
/**
 * GOAP-style loop using Judge-centric architecture.
 * Demonstrates that GOAP is a special case of AgentLoop.
 */
public class GoapLoop<S> implements AgentLoop<Goal<S>, S> {

    private final List<PlanAction<S>> availableActions;
    private final Planner<S> planner;
    private final int maxReplanAttempts;

    @Override
    public S execute(Goal<S> goal, JudgmentContext initialContext) {
        @SuppressWarnings("unchecked")
        S currentState = (S) initialContext.state().get("worldState");

        for (int attempt = 0; attempt < maxReplanAttempts; attempt++) {
            // Plan
            Optional<Plan<S>> maybePlan = planner.plan(currentState, goal, availableActions);

            if (maybePlan.isEmpty()) {
                // No plan found - cannot achieve goal
                return currentState;
            }

            Plan<S> plan = maybePlan.get();

            // Execute plan
            while (!plan.isEmpty()) {
                PlanAction<S> action = plan.nextAction();
                JudgmentContext ctx = JudgmentContext.of(currentState);

                // canExecute check (GOAP precondition)
                Judgment canExecute = action.checkPreconditions(currentState, ctx);
                if (!canExecute.pass()) {
                    // Precondition failed - need to replan
                    break;
                }

                // Execute (Generator equivalent)
                S newState = action.execute(currentState, ctx);

                // didSucceed check (GOAP effect verification)
                Judgment didSucceed = action.verifyEffects(currentState, newState, ctx);
                if (!didSucceed.pass()) {
                    // Effect verification failed - need to replan
                    currentState = newState;  // State may have partially changed
                    break;
                }

                currentState = newState;
                plan = plan.advance();

                // isComplete check (GOAP goal)
                Judgment isComplete = goal.isSatisfied(currentState, ctx);
                if (isComplete.pass()) {
                    return currentState;
                }
            }
        }

        return currentState;
    }

    // Factory method showing the Judge-centric equivalence
    public static <S> AgentLoop<Goal<S>, S> asAgentLoop(
            List<PlanAction<S>> actions,
            Planner<S> planner) {

        return DefaultAgentLoop.<Goal<S>, S>builder()
            .generator((goal, ctx) -> {
                // Planning as generation
                GoapLoop<S> goapLoop = new GoapLoop<>(actions, planner, 3);
                return goapLoop.execute(goal, ctx);
            })
            .canExecute(Judges.alwaysPass())  // Planning handles preconditions
            .shouldExecute(Judges.alwaysPass())
            .didSucceed(Judges.alwaysPass())  // Planning handles effects
            .isComplete(ctx -> {
                @SuppressWarnings("unchecked")
                Goal<S> goal = (Goal<S>) ctx.input();
                return goal.judge(ctx);
            })
            .refiner(Refiner.identity())
            .strategy(new SequentialStrategy())
            .maxIterations(1)  // Single planning cycle
            .build();
    }
}
```

### 4.4 Research Deliverable

**Module**: `harness-planning`
- `PlanAction<S>` interface
- `Goal<S>` interface
- `Planner<S>` interface
- `AStarPlanner<S>` implementation
- `GoapLoop<S>` implementation
- `GoapLoop.asAgentLoop()` factory demonstrating subsumption

---

## Part 5: Validation

### 5.1 Test Cases

**Unit tests proving equivalence**:

1. **Simple linear plan**: A → B → C → Goal
2. **Branching plan**: Multiple paths, A* selects optimal
3. **Precondition failure**: Replanning when world changes
4. **Effect verification**: Detecting when action didn't work
5. **No plan exists**: Graceful termination

**Integration tests**:

1. **Classic GOAP scenario**: Game AI enemy planning (move, aim, shoot, reload)
2. **SDLC scenario**: Build → Test → Deploy workflow
3. **Comparison with manual**: Same result as hand-coded state machine

### 5.2 Expressiveness Proof

**Demonstrate that anything Embabel can express, we can express**:

| Embabel Pattern | Our Implementation | Test |
|-----------------|-------------------|------|
| Status-based loop | `LoopStatus` + `TerminationReason` | ✓ |
| Early termination | `isComplete()` Judge | ✓ |
| Action preconditions | `PlanAction.checkPreconditions()` → Judge | ✓ |
| Effect verification | `PlanAction.verifyEffects()` → Judge | ✓ |
| A* planning | `AStarPlanner` | ✓ |
| Replanning on failure | `GoapLoop` retry logic | ✓ |

### 5.3 Research Deliverable

**Document**: `research/GOAP-VALIDATION.md`
- Test results
- Expressiveness proof
- Performance comparison (if relevant)
- Edge cases handled

---

## Part 6: Publication-Ready Documentation

### 6.1 The Subsumption Argument

**For Java AI community / Embabel reviewers**:

> We do not claim to replace Embabel or diminish its contributions. Rather, we observe that Embabel's elegant state machine patterns can be **expressed** using a more general abstraction: the Judge-centric AgentLoop.
>
> This has practical value:
> 1. **Unified mental model**: One abstraction for GOAP, LLM agents, and hybrid systems
> 2. **Composability**: GOAP actions can be mixed with LLM-generated actions
> 3. **Gradual migration**: Existing GOAP systems can adopt Judges incrementally
>
> GOAP's boolean conditions (`preconditions: Map<String, Boolean>`) are a degenerate case of Judgments (`Judgment(verdict, score, feedback)`). This generalization enables:
> - Fuzzy preconditions (score thresholds)
> - Uncertainty handling (`Verdict.UNCERTAIN`)
> - Feedback-driven replanning (Judge feedback → action refinement)

### 6.2 Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      JUDGE-CENTRIC ARCHITECTURE                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        AgentLoop<I, O>                               │   │
│  │                                                                       │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                │   │
│  │  │ canExecute() │  │ generator()  │  │ didSucceed() │                │   │
│  │  │    Judge     │  │  Generator   │  │    Judge     │                │   │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                │   │
│  │         │                 │                 │                         │   │
│  │         │    ┌────────────┴────────────┐    │                         │   │
│  │         │    │      isComplete()       │    │                         │   │
│  │         │    │         Judge           │    │                         │   │
│  │         │    └────────────┬────────────┘    │                         │   │
│  │         │                 │                 │                         │   │
│  │         └─────────────────┴─────────────────┘                         │   │
│  │                           │                                           │   │
│  │                    ┌──────┴──────┐                                    │   │
│  │                    │  Strategy   │                                    │   │
│  │                    └─────────────┘                                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                         SPECIAL CASES (DEGENERATE)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────────────┐  ┌───────────────────┐  ┌───────────────────┐       │
│  │   GOAP / Embabel  │  │   LLM Agent       │  │  Evaluator-Opt    │       │
│  │   (Pattern 3)     │  │   (Pattern 1)     │  │  (Pattern 2)      │       │
│  ├───────────────────┤  ├───────────────────┤  ├───────────────────┤       │
│  │ canExecute =      │  │ canExecute =      │  │ canExecute =      │       │
│  │  preconditions    │  │  alwaysPass       │  │  alwaysPass       │       │
│  │                   │  │                   │  │                   │       │
│  │ generator =       │  │ generator =       │  │ generator =       │       │
│  │  action.execute   │  │  LLM.call         │  │  LLM.call         │       │
│  │                   │  │                   │  │                   │       │
│  │ didSucceed =      │  │ didSucceed =      │  │ didSucceed =      │       │
│  │  effect verify    │  │  hasToolCalls     │  │  qualityJudge     │       │
│  │                   │  │                   │  │                   │       │
│  │ isComplete =      │  │ isComplete =      │  │ isComplete =      │       │
│  │  goal conditions  │  │  noToolCalls      │  │  score >= 0.9     │       │
│  │                   │  │                   │  │                   │       │
│  │ strategy =        │  │ strategy =        │  │ strategy =        │       │
│  │  A* Planner       │  │  Sequential       │  │  Sequential       │       │
│  └───────────────────┘  └───────────────────┘  └───────────────────┘       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Execution Timeline

| Phase | Duration | Deliverables |
|-------|----------|--------------|
| **Phase 1: GOAP Fundamentals** | 1-2 days | `GOAP-FUNDAMENTALS.md` |
| **Phase 2: Embabel Analysis** | 1-2 days | `EMBABEL-PATTERN-ANALYSIS.md` |
| **Phase 3: Formal Mapping** | 2-3 days | `GOAP-JUDGE-MAPPING.md` |
| **Phase 4: Implementation** | 3-5 days | `harness-planning` module |
| **Phase 5: Validation** | 2-3 days | `GOAP-VALIDATION.md`, tests |
| **Phase 6: Documentation** | 1-2 days | Final writeup, diagrams |

**Total**: ~2 weeks of focused work

---

## Success Criteria

1. [ ] GOAP fundamentals documented from academic sources
2. [ ] Embabel patterns extracted (clean-room, no code copying)
3. [ ] Formal mapping with proof sketches
4. [ ] `harness-planning` module with:
   - [ ] `PlanAction<S>` interface
   - [ ] `Goal<S>` interface
   - [ ] `AStarPlanner<S>` implementation
   - [ ] `GoapLoop<S>` implementation
   - [ ] `GoapLoop.asAgentLoop()` factory
5. [ ] Unit tests proving equivalence
6. [ ] Integration test: SDLC workflow as GOAP
7. [ ] Architecture diagram showing subsumption
8. [ ] Publication-ready explanation for reviewers

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| **IP contamination from Embabel** | Read-only analysis, extract patterns only, no code copying |
| **GOAP has capabilities we can't express** | Document limitations explicitly, don't overclaim |
| **Performance gap vs native GOAP** | Benchmark, but note abstraction cost is acceptable |
| **Community pushback** | Position as "generalization" not "replacement" |

---

*Last Updated: 2026-01-13*
