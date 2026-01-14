# GOAP Research Learnings

**Started**: 2026-01-13
**Status**: In Progress

## Summary
Research into Goal-Oriented Action Planning (GOAP) to determine relevance for agent-harness and prove subsumption under Judge-centric architecture.

## Key Finding

**GOAP is academically valid but not what production CLI agents use.**

| Agent | Pattern Used | GOAP? |
|-------|--------------|-------|
| Claude Code | Turn-Limited | No |
| Gemini CLI | Turn-Limited + Finish Tool | No |
| Aider | Exception-Driven | No |
| OpenAI Swarm | Turn-Limited | No |
| Embabel | State Machine + GOAP | Yes (only one) |

## What GOAP Actually Is

### Origin
- Created by Jeff Orkin at Monolith Productions for F.E.A.R. (2005)
- Based on STRIPS planning (Fikes & Nilsson, 1971)
- Uses A* search over state space

### Core Components
1. **World State**: Set of boolean facts (e.g., `{hasWeapon=true, enemyVisible=true}`)
2. **Actions**: Preconditions + Effects + Cost
3. **Goals**: Desired world state
4. **Planner**: A* search finds action sequence to achieve goal

### Key Papers
| Paper | Author | Year | Key Contribution |
|-------|--------|------|------------------|
| Applying GOAP to Games | Orkin | 2003 | Original GOAP design |
| Three States and a Plan | Orkin | 2006 | F.E.A.R. implementation |
| STRIPS | Fikes & Nilsson | 1971 | Theoretical foundation |
| A* Algorithm | Hart, Nilsson, Raphael | 1968 | Heuristic search |

## Why GOAP Works for Game AI

| Characteristic | Game AI | Why GOAP Fits |
|----------------|---------|---------------|
| State space | ~20 booleans | Finite, enumerable |
| Actions | ~15 predefined | Known in advance |
| Effects | Deterministic | `shoot → target.health -= 10` |
| World model | Complete | Game knows all state |
| Planning horizon | Short | 3-5 actions typical |

## Why GOAP Fails for SDLC Agents

| Characteristic | SDLC Tasks | Why GOAP Breaks |
|----------------|------------|-----------------|
| State space | Infinite | "Codebase correctness" isn't boolean |
| Actions | Unbounded | `FixBug(file, line)` → thousands |
| Effects | Non-deterministic | Same edit may or may not fix |
| World model | Partial | Agent can't see all code context |
| Planning horizon | Unknown | Bug fix could be 1 or 100 steps |

### Action Explosion Example
To fix a bug with GOAP, you'd need actions like:
- `ReadFile(path)` for every file
- `EditLine(file, line, content)` for every line × every possible edit
- `RunTests()` with non-deterministic outcome

This explodes to millions of combinations vs. letting the LLM reason.

## Embabel's Actual Thesis (Reconstructed)

Embabel's real claim is **not** "GOAP is good for LLM agents."

It's: **"LLMs must NOT control agent execution flow directly. Deterministic supervisory control is required for boundedness, reproducibility, and auditability."**

This is valid within domains where:
1. Action space is finite
2. Preconditions are enumerable
3. Effects are assertable
4. World model is sufficiently complete

## Subsumption Proof

GOAP is a **degenerate case** of Judge-centric architecture:

```
GOAP = Judge-centric + {
  boolean-only Judges (no scores, no uncertainty)
  A* Strategy (instead of Sequential)
  deterministic Generator (instead of LLM)
}
```

### Mapping
| GOAP Concept | Judge-centric Equivalent |
|--------------|-------------------------|
| Precondition | `canExecute()` Judge returning boolean |
| Effect | `didSucceed()` Judge returning boolean |
| Goal | `isComplete()` Judge returning boolean |
| A* Planner | Strategy that uses heuristic search |
| World State | Generic state object `S` |

## Reference Implementations Studied

### JavaGOAP (MIT License)
- **Repository**: https://github.com/ph1387/JavaGOAP
- **Quality**: Well-documented, based on Orkin
- **Key Classes**:
  - `GoapAgent` - Agent abstraction
  - `GoapPlanner` - A* planning
  - `GoapState` - World state representation
  - `GoapAction` - Action with preconditions/effects

### jgoap (MIT License)
- **Repository**: https://github.com/fdefelici/jgoap
- **Quality**: Minimal, Maven-based
- **Approach**: Cleaner separation of concerns

### gdx-ai (Apache 2.0)
- **Repository**: https://github.com/libgdx/gdx-ai
- **Quality**: Production quality A* implementation
- **Key Classes**: `IndexedAStarPathFinder`, `Heuristic`

## Implementation Decision

### What We're Building (~190 LOC)
```java
// Core interfaces
interface PlanAction<S> {
    String name();
    boolean checkPreconditions(S state);
    S applyEffects(S state);
    double cost();
}

interface Goal<S> {
    boolean isSatisfied(S state);
    default double heuristic(S state) { return 0; }
}

// A* planner
class AStarPlanner<S> {
    Optional<List<PlanAction<S>>> plan(S initial, Goal<S> goal, Collection<PlanAction<S>> actions);
}

// Execution loop
class GoapLoop<S> {
    S execute(S initial, Goal<S> goal, List<PlanAction<S>> actions);
}
```

### What We're NOT Building
- ❌ `IWorldStateProvider` abstraction (unnecessary)
- ❌ `IPlanExecutor` with replanning (over-engineered)
- ❌ Multi-agent coordination (scope creep)
- ❌ Hierarchical planning (different paradigm)
- ❌ DSL for conditions (Java lambdas suffice)

## Test Strategy

### GameAiGoapTest
Classic GOAP domain to prove it works:
- State: `{hasWeapon, hasAmmo, enemyVisible, enemyDead}`
- Actions: `GetWeapon`, `GetAmmo`, `Attack`, `TakeCover`
- Goal: `enemyDead = true`

### HoroscopeGoapTest (Embabel Example)
Port of Embabel example to prove subsumption:
- State: `{dateKnown, signKnown, horoscopeGenerated}`
- Actions: `GetDate`, `DetermineSign`, `GenerateHoroscope`
- LLM called inside `GenerateHoroscope` action

### SdlcContrastTest
Demonstrates why GOAP doesn't fit SDLC:
- Shows action explosion
- Shows non-boolean state
- Shows non-deterministic effects
- Concludes: Turn-Limited is better

## Clean Framing for Reviews

> "GOAP-style planners are appropriate when the action space is enumerable and effects are well-defined.
>
> Modern SDLC agents violate those assumptions.
>
> Therefore, GOAP is useful as a **theoretical baseline**, not as a production control strategy.
>
> Our implementation demonstrates that GOAP is a special case of Judge-centric architecture, where Judges are boolean, the Generator is deterministic, and the Strategy is A*."

## Files Created
- `plans/research/GOAP-EMBABEL-ANALYSIS.md` - Full 11-part analysis
- `plans/research/GOAP-SOURCES.md` - Bibliography
- `plans/research/RESEARCH-AGENT-PROMPT.md` - Research agent prompt
- `~/research/papers/goap/` - Downloaded papers

## JavaGOAP vs Academic Literature Analysis

### Groningen Report Key Insights (2008)

The University of Groningen report provides excellent academic context:

1. **STRIPS Foundation**: GOAP is based on STRIPS with states as predicates and actions as precondition/postcondition pairs

2. **A* Algorithm Definition**:
   - `f(s) = g(s) + h(s)` where g(s) is accumulated cost, h(s) is heuristic estimate
   - Heuristic must be admissible (never overestimate)
   - First solution found is guaranteed optimal

3. **Key Success Factor from F.E.A.R.**:
   > "They kept the planning at a high level, so that the actions remained quite abstract and the number of actions remained very limited"

4. **Closed World Assumption**: The paper explicitly identifies this as a limitation - GOAP assumes complete knowledge

5. **Hybrid Paradigm Recommendation**: Even in 2008, the paper concludes "planning alone is not enough" and recommends combining with reactive behavior

### JavaGOAP Implementation Observations

After analyzing JavaGOAP (MIT License):

| Aspect | JavaGOAP | Academic A* | Our Design |
|--------|----------|-------------|------------|
| **Search** | Graph-based path tracking | State-space A* | Clean state-space A* |
| **State** | `GoapState` with String effect | Predicate set | Generic `S` type |
| **Actions** | Abstract class with many methods | Precondition + Effect | Simple interface |
| **Complexity** | ~500 LOC | ~100 LOC | ~80 LOC |

**JavaGOAP Design Choices**:
- Uses `HashSet<GoapState>` for preconditions/effects
- `GoapState` has `importance`, `effect` (String), `value` (Object)
- Builds graph with all paths to each node (memory-heavy)
- Requires extending abstract `GoapAction` class (8+ abstract methods)

**Our Simplification**:
- Use generic `S` for state (any type)
- `PlanAction<S>` as interface with 4 methods only
- Direct A* over state space (not graph construction)
- Java lambdas for preconditions/effects instead of class hierarchies

### Clean-Room Implementation Approach

Based on Groningen paper's pseudocode (page 12), our A* can be:

```java
// Pseudocode from Groningen, adapted to Java
public Optional<List<PlanAction<S>>> plan(S initial, Goal<S> goal, Collection<PlanAction<S>> actions) {
    PriorityQueue<Node<S>> open = new PriorityQueue<>(comparingDouble(Node::fScore));
    Set<S> closed = new HashSet<>();

    open.add(new Node<>(initial, List.of(), 0, goal.heuristic(initial)));

    while (!open.isEmpty()) {
        Node<S> current = open.poll();

        if (goal.isSatisfied(current.state())) {
            return Optional.of(current.path());
        }

        if (closed.contains(current.state())) continue;
        closed.add(current.state());

        for (PlanAction<S> action : actions) {
            if (action.checkPreconditions(current.state())) {
                S newState = action.applyEffects(current.state());
                double g = current.gScore() + action.cost();
                double h = goal.heuristic(newState);
                List<PlanAction<S>> newPath = append(current.path(), action);
                open.add(new Node<>(newState, newPath, g, h));
            }
        }
    }
    return Optional.empty();
}
```

This is ~40 lines for the core algorithm, matching the academic description exactly.

## Cross-Language GOAP Implementation Analysis

### Implementations Studied

| Project | Language | License | LOC | State Pattern | A* Approach |
|---------|----------|---------|-----|---------------|-------------|
| GPGOAP | C | Apache 2.0 | 649 | Bitmask (int64) | Direct state-space |
| JavaGOAP | Java | MIT | ~500 | HashSet<GoapState> | Graph construction |
| ReGoap | C# | MIT | 5,569 | ConcurrentDictionary<T,W> | Backward chaining |
| cppGOAP | C++ | LGPL v3 | 778 | unordered_map<int,bool> | Standard A* |
| goap | Go | MIT | 1,281 | Sorted key-value pairs | Direct with XOR hash |
| GOApy | Python | BSD | 1,924 | Dict | NetworkX A* |

### GPGOAP (C) - Most Efficient

**Source**: `/home/mark/research/reference/GPGOAP/`

The most elegant implementation. Uses 64-bit bitmasks for O(1) operations:

```c
typedef struct {
    bfield_t values;    // 64 boolean atoms
    bfield_t dontcare;  // Mask for atoms that don't matter
} worldstate_t;
```

**Key Pattern - O(1) Precondition Check** (goap.c:192):
```c
const bfield_t care = (pre.dontcare ^ -1LL);
const bool met = ((pre.values & care) == (fr.values & care));
```

**Why This Is Optimal**:
- 64 atoms fit in a single register
- XOR with -1 inverts dontcare to "do care" mask
- Single AND + comparison for precondition check
- No allocations during planning

**Limitation**: Fixed to 64 boolean atoms. Real game AI rarely needs more.

### ReGoap (C#) - Most Production-Ready

**Source**: `/home/mark/research/reference/ReGoap/`

Unity-focused with enterprise patterns:

```csharp
public class ReGoapState<T, W> {
    private ConcurrentDictionary<T, W> values;
    private readonly ConcurrentDictionary<T, W> bufferA;
    private readonly ConcurrentDictionary<T, W> bufferB;  // Double buffering

    // Object pooling
    private static Stack<ReGoapState<T, W>> cachedStates;
    public static void Warmup(int count) { ... }
    public void Recycle() { ... }
}
```

**Key Patterns**:
1. **Generic `<T, W>`**: Keys and values can be any type
2. **Thread Safety**: `ConcurrentDictionary` with `lock` statements
3. **Object Pooling**: `Warmup(count)` pre-allocates, `Recycle()` returns to pool
4. **Double Buffering**: `ReplaceWithMissingDifference` swaps buffers to avoid allocation

**Overkill For Us**: These patterns are for Unity's frame-rate-sensitive games. Our research module doesn't need GC optimization.

### goap (Go) - Cleanest Design

**Source**: `/home/mark/research/reference/goap/`

Most idiomatic implementation with zero-allocation design:

```go
type Action interface {
    Simulate(current *State) (require, outcome *State)
    Cost() float32
}

func Plan(start, goal *State, actions []Action) ([]Action, error) {
    heap := acquireHeap()  // Pool allocation
    defer heap.Release()   // Auto-return to pool
    ...
}
```

**Key Patterns**:
1. **Interface-Based**: Just 2 methods (`Simulate`, `Cost`)
2. **XOR Hashing**: State identity via XOR of key-value hashes
3. **sync.Pool**: Heap structures recycled between plans
4. **maxDepth = 100**: Safety limit prevents infinite loops

**Action Interface Insight** (planner.go:14-22):
```go
type Action interface {
    // Returns (preconditions, effects) given current state
    Simulate(current *State) (require, outcome *State)
    Cost() float32
}
```

This `Simulate` pattern is elegant - preconditions and effects can be dynamic based on current state, not static.

### cppGOAP (C++) - Standard Textbook

**Source**: `/home/mark/research/reference/cppGOAP/`

**WARNING**: LGPL v3 license - derivative works must be LGPL. Clean-room only.

```cpp
class Action {
    std::unordered_map<int, bool> preconditions_;
    std::unordered_map<int, bool> effects_;
public:
    bool operableOn(const WorldState& ws) const;
    WorldState actOn(const WorldState& ws) const;
};
```

**Key Observation**: Returns actions in REVERSE ORDER (caller must reverse-iterate).

### GOApy (Python) - Graph-Based

**Source**: `/home/mark/research/reference/GOApy/`

Delegates to NetworkX for A*:

```python
class Planner:
    def plan(self, state: dict, goal: dict) -> list:
        self.__generate_states(self.actions, self.world_state, self.goal)
        self.__generate_transitions(self.actions, self.states)
        self.graph = Graph(self.states, self.transitions)
        return self.graph.path(world_state_node, goal_node)  # nx.astar_path
```

**Pattern**: Build full graph, then use library A*. Higher memory, simpler code.

### Synthesis: Best Patterns for Java

| Aspect | Best Pattern | Source | Our Choice |
|--------|--------------|--------|------------|
| **State** | Generic `S` | Go | `S extends Object` with `equals/hashCode` |
| **Action Interface** | 2-4 methods | Go, C | 4 methods: `name`, `checkPreconditions`, `applyEffects`, `cost` |
| **A* Algorithm** | Direct state-space | C, Go | `PriorityQueue<Node<S>>` + `Set<S>` closed |
| **Heuristic** | Optional on Goal | Go | `default double heuristic(S) { return 0; }` |
| **Memory** | No pooling | N/A | Not needed for research module |
| **Thread Safety** | None | N/A | Planning is single-threaded |

### What We're NOT Copying

| Pattern | Source | Why Skip |
|---------|--------|----------|
| Bitmask state | GPGOAP | Limits to 64 booleans, Java generics better |
| Object pooling | ReGoap | Overkill for research module |
| ConcurrentDictionary | ReGoap | Single-threaded planning |
| Graph construction | JavaGOAP, GOApy | Direct A* is simpler and faster |
| LGPL code | cppGOAP | License incompatible |
| Abstract class with 8+ methods | JavaGOAP | Interface with 4 methods suffices |

### Final Implementation Spec (~190 LOC)

```java
// PlanAction.java (~15 lines)
public interface PlanAction<S> {
    String name();
    boolean checkPreconditions(S state);
    S applyEffects(S state);
    default double cost() { return 1.0; }
}

// Goal.java (~10 lines)
public interface Goal<S> {
    boolean isSatisfied(S state);
    default double heuristic(S state) { return 0.0; }
}

// PlanNode.java (~25 lines) - Internal record
record PlanNode<S>(S state, List<PlanAction<S>> path, double g, double h) {
    double f() { return g + h; }
}

// AStarPlanner.java (~60 lines)
public class AStarPlanner<S> {
    public Optional<List<PlanAction<S>>> plan(
        S initial, Goal<S> goal, Collection<PlanAction<S>> actions
    ) {
        // Standard A* with PriorityQueue
    }
}

// GoapLoop.java (~40 lines) - Executes plan with precondition re-checking
public class GoapLoop<S> {
    public S execute(S initial, Goal<S> goal, List<PlanAction<S>> actions) {
        // Plan, then execute, replan on precondition failure
    }
}
```

## GraphCompositionStrategy vs GOAP

### FAQ: How does GraphCompositionStrategy differ from GOAP?

This question comes up because both involve "graphs" and "planning". Here's the key distinction:

| Aspect | **GOAP** | **GraphCompositionStrategy** |
|--------|----------|------------------------------|
| **What it is** | AI planning algorithm from game development | Composition layer for agent workflows |
| **Purpose** | Find optimal action sequence to reach a goal | Define and execute a fixed workflow |
| **Graph meaning** | World state transitions (planner searches) | Work unit connections (user defines) |
| **Runtime behavior** | Planner **discovers** path | Executor **follows** path |
| **Nodes represent** | World states (boolean predicates) | Work units (functions, loops) |
| **Edges represent** | Actions that transform state | Data flow between nodes |
| **Cycles** | Avoided (A* search finds shortest path) | Supported (e.g., retry loops) |
| **LLM role** | None - purely algorithmic | Nodes can contain LLM calls |

### Key Conceptual Difference

**GOAP**: "Given a goal and available actions, **find** the sequence of actions to achieve the goal."

```
GOAP Planner:
  Goal: "Code is tested"
  Initial: "Code is written"
  Actions: [RunTests, FixBug, WriteCode]

  Planner DISCOVERS: WriteCode → RunTests → FixBug → RunTests
```

**GraphCompositionStrategy**: "Given a workflow I've defined, **execute** it."

```
GraphCompositionStrategy:
  User DEFINES:
    start → code → test → [if passed] → finish
                        → [if failed] → code (cycle back)

  Executor FOLLOWS the defined edges
```

### When Would You Want GOAP?

If you need the **planner to discover** the workflow at runtime based on current state and available actions. This is rare in SDLC agents where workflows are typically predefined (plan → code → test → deploy).

### When Would You Want GraphCompositionStrategy?

When you want explicit control over workflow structure with:
- Conditional branching based on node output
- Retry loops for failed operations
- Composition of AgentLoops into larger workflows
- Observable execution path for debugging

### Why We Have Both (Eventually)

- `GraphCompositionStrategy` (harness-patterns): User-defined workflows with graph structure
- `GoapLoop` (harness-planning): Runtime discovery when action space is enumerable

They're complementary, not competing. Most SDLC agents use GraphCompositionStrategy-style fixed workflows. GOAP is for domains with finite, enumerable action spaces where runtime discovery is valuable.

---

## Next Steps
1. ~~Clone JavaGOAP for pattern study~~ ✓
2. ~~Analyze cross-language GOAP implementations~~ ✓
3. Implement `harness-planning` module
4. Write test suite proving subsumption
5. Update progress table when complete
