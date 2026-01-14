# Plan: Graph-Defined Execution as Composition Layer

**Date**: 2026-01-14
**Status**: Approved

## Context

Following the two-axis taxonomy model, graph-defined execution is an **orthogonal dimension** to termination patterns, not Pattern 9. This plan adds graph composition as a layer ABOVE existing AgentLoop implementations.

### Key Design Principle

**GraphStrategy does NOT implement AgentLoop.**

| Abstraction | Purpose | Implements AgentLoop? |
|-------------|---------|----------------------|
| TurnLimitedLoop | Termination pattern | Yes |
| EvaluatorOptimizerLoop | Termination pattern | Yes |
| StateMachineLoop | Termination pattern | Yes |
| **GraphStrategy** | **Composition layer** | **No** |

Graph is a composition layer that can HOST AgentLoop implementations inside nodes.

---

## Architecture

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

---

## Module Structure

Add new package to `harness-patterns` (not a new module - keeps dependency graph simple):

```
harness-patterns/src/main/java/org/springaicommunity/agents/harness/patterns/
├── turnlimited/          # Existing
├── evaluator/            # Existing
├── statemachine/         # Existing
└── graph/                # NEW
    ├── GraphNode.java
    ├── GraphEdge.java
    ├── GraphStrategy.java
    ├── GraphStrategyBuilder.java
    ├── GraphContext.java
    ├── GraphResult.java
    └── GraphExecutionException.java
```

---

## Core Interfaces

### 1. GraphNode (work unit)

```java
package org.springaicommunity.agents.harness.patterns.graph;

/**
 * A node in a graph strategy. Can wrap a function or an AgentLoop.
 * Does NOT extend AgentLoop - it's at a different abstraction level.
 */
public interface GraphNode<I, O> {

    String name();

    O execute(GraphContext context, I input);

    /**
     * Create a node from a simple function.
     */
    static <I, O> GraphNode<I, O> of(String name, BiFunction<GraphContext, I, O> function) {
        return new FunctionGraphNode<>(name, function);
    }

    /**
     * Create a node that wraps an existing AgentLoop.
     * The loop executes inside this node.
     */
    static <R extends LoopResult> GraphNode<String, R> fromLoop(
            String name,
            AgentLoop<R> loop,
            ChatClient chatClient,
            List<ToolCallback> tools) {
        return new LoopGraphNode<>(name, loop, chatClient, tools);
    }
}
```

### 2. GraphEdge (conditional routing)

```java
/**
 * A directed edge with optional condition and transformation.
 */
public record GraphEdge<T>(
    GraphNode<?, ?> target,
    Predicate<T> condition,
    Function<T, ?> transformer
) {
    public static <T> GraphEdge<T> to(GraphNode<?, ?> target) {
        return new GraphEdge<>(target, t -> true, Function.identity());
    }

    public GraphEdge<T> when(Predicate<T> condition) {
        return new GraphEdge<>(target, condition, transformer);
    }

    public <U> GraphEdge<T> transform(Function<T, U> transformer) {
        return new GraphEdge<>(target, condition, transformer);
    }
}
```

### 3. GraphStrategy (execution container)

```java
/**
 * Graph-defined execution strategy. NOT an AgentLoop.
 * Composes nodes and edges, executes via graph traversal.
 */
public class GraphStrategy<I, O> {

    private final String name;
    private final Map<String, GraphNode<?, ?>> nodes;
    private final Multimap<String, GraphEdge<?>> edges;
    private final String startNodeName;
    private final String finishNodeName;
    private final int maxIterations;

    /**
     * Execute the graph strategy.
     * @return GraphResult (NOT a LoopResult - different abstraction)
     */
    public GraphResult<O> execute(I input) {
        GraphContext context = new GraphContext();
        GraphNode<?, ?> current = nodes.get(startNodeName);
        Object currentInput = input;
        int iterations = 0;
        List<String> path = new ArrayList<>();

        while (!current.name().equals(finishNodeName)) {
            if (++iterations > maxIterations) {
                return GraphResult.maxIterationsExceeded(path, iterations);
            }

            path.add(current.name());
            Object output = current.executeUnsafe(context, currentInput);

            GraphEdge<?> edge = resolveEdge(current.name(), output);
            if (edge == null) {
                // Graph topology failure - distinct from loop failures
                return GraphResult.stuckInNode(current.name(), path);
            }

            current = edge.target();
            currentInput = edge.transformer().apply(output);
        }

        // Execute finish node
        path.add(current.name());
        Object finalOutput = current.executeUnsafe(context, currentInput);

        return GraphResult.completed((O) finalOutput, path);
    }

    public static <I, O> GraphStrategyBuilder<I, O> builder(String name) {
        return new GraphStrategyBuilder<>(name);
    }
}
```

### 4. GraphResult (graph-specific result)

```java
/**
 * Result of graph execution. NOT a LoopResult.
 * Contains graph-specific information (path taken, stuck node, etc.)
 */
public record GraphResult<O>(
    GraphStatus status,
    O output,
    List<String> pathTaken,
    String stuckNodeName,  // Only set if status == STUCK_IN_NODE
    int iterations,
    Duration duration
) {
    public enum GraphStatus {
        COMPLETED,
        STUCK_IN_NODE,      // Graph topology failure
        MAX_ITERATIONS,     // Safety limit
        ERROR
    }

    public static <O> GraphResult<O> completed(O output, List<String> path) { ... }
    public static <O> GraphResult<O> stuckInNode(String nodeName, List<String> path) { ... }
    public static <O> GraphResult<O> maxIterationsExceeded(List<String> path, int iterations) { ... }
}
```

### 5. GraphStrategyBuilder (fluent DSL)

```java
public class GraphStrategyBuilder<I, O> {

    public GraphStrategyBuilder<I, O> node(String name, BiFunction<GraphContext, ?, ?> function) { ... }

    public GraphStrategyBuilder<I, O> loopNode(String name, AgentLoop<?> loop,
                                                ChatClient chatClient, List<ToolCallback> tools) { ... }

    public GraphStrategyBuilder<I, O> startNode(String name) { ... }

    public GraphStrategyBuilder<I, O> finishNode(String name) { ... }

    public EdgeBuilder edge(String from) { ... }

    public GraphStrategyBuilder<I, O> maxIterations(int max) { ... }

    public GraphStrategy<I, O> build() { ... }

    public class EdgeBuilder {
        public GraphStrategyBuilder<I, O> to(String target) { ... }
        public <T> EdgeBuilder when(Predicate<T> condition) { ... }
        public <T, U> EdgeBuilder transform(Function<T, U> transformer) { ... }
    }
}
```

---

## Usage Example

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

if (result.status() == GraphStatus.COMPLETED) {
    System.out.println("Success: " + result.output());
    System.out.println("Path: " + result.pathTaken());
} else if (result.status() == GraphStatus.STUCK_IN_NODE) {
    System.out.println("Graph error: stuck in " + result.stuckNodeName());
}
```

---

## Files to Create

| File | Purpose | Lines (est) |
|------|---------|-------------|
| `GraphNode.java` | Node interface + factory methods | ~60 |
| `FunctionGraphNode.java` | Function-based node impl | ~30 |
| `LoopGraphNode.java` | AgentLoop wrapper node impl | ~50 |
| `GraphEdge.java` | Edge record with condition | ~40 |
| `GraphContext.java` | Execution context | ~40 |
| `GraphResult.java` | Result record | ~60 |
| `GraphStrategy.java` | Main execution container | ~120 |
| `GraphStrategyBuilder.java` | Fluent DSL builder | ~150 |
| `GraphExecutionException.java` | Graph-specific exceptions | ~30 |
| **Total** | | **~580 lines** |

---

## Test Plan

### Unit Tests

1. **GraphNode tests**
   - Function node executes correctly
   - Loop node wraps AgentLoop correctly

2. **GraphEdge tests**
   - Condition filtering works
   - Transformation applies

3. **GraphStrategy tests**
   - Linear graph (A -> B -> C) executes correctly
   - Cyclic graph (A -> B -> A with condition) terminates
   - Max iterations enforced
   - Stuck node detected

### Integration Tests

1. **Graph with TurnLimitedLoop nodes**
   - Plan -> Code -> Test -> Finish workflow
   - Test failure triggers code retry

2. **Graph with EvaluatorOptimizerLoop node**
   - Refinement loop inside graph node

---

## Verification Steps

1. Run `mvn clean compile` - all code compiles
2. Run `mvn test` - all unit tests pass
3. Run `mvn verify -Pfailsafe` - integration tests pass
4. Create example in `harness-examples` demonstrating graph composition

---

## Roadmap Placement

Add to `/home/mark/projects/agent-harness/plans/PLAN.md` as:

**Step N: Graph-Defined Execution (Composition Layer)**

| Aspect | Value |
|--------|-------|
| Priority | MEDIUM |
| Entry Criteria | Core loops (TurnLimited, EvaluatorOptimizer, StateMachine) implemented and tested |
| Exit Criteria | GraphStrategy can compose existing loops, all tests pass |
| Effort | 4-6 days |

**Wording**: "Graph-defined execution **as a composition layer over existing loops**" (not "new loop pattern")

---

## What We're NOT Building

1. GraphStrategy implementing AgentLoop
2. External graph library dependency (JGraphT, Guava Graph)
3. Graph persistence or database
4. Distributed graph traversal
5. GOAP or algorithmic planning

---

## Design Rationale: Relationship to Koog

### Understanding Koog's Architecture

JetBrains Koog uses **fine-grained nodes** where each node typically performs one LLM call:

```
Koog Node Granularity:
┌─────────────────────────────────────────────────────────────────┐
│  ReAct Strategy (Koog)                                          │
│  ┌─────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────┐ │
│  │ Start   │──>│ CallLLM     │──>│ ExecuteTool │──>│ Finish  │ │
│  │         │   │ (1 call)    │   │ (1 call)    │   │         │ │
│  └─────────┘   └──────┬──────┘   └──────┬──────┘   └─────────┘ │
│                       │                  │                      │
│                       └────────<─────────┘  (cycle via edges)   │
└─────────────────────────────────────────────────────────────────┘
```

- Each node = 1 atomic operation
- Cycles handled through graph edge conditions
- 10 turns = graph traverses ~30 nodes (start -> llm -> tool -> result -> llm -> ...)

### Our Proposal: Coarse-Grained Nodes

Our GraphStrategy allows **coarse-grained nodes** that can wrap entire AgentLoops:

```
Our Node Granularity:
┌─────────────────────────────────────────────────────────────────┐
│  Coding Workflow (agent-harness)                                │
│  ┌─────────┐   ┌─────────────────────────┐   ┌─────────┐       │
│  │ Start   │──>│      CodingNode         │──>│ Finish  │       │
│  │         │   │  ┌───────────────────┐  │   │         │       │
│  │         │   │  │ TurnLimitedLoop   │  │   │         │       │
│  │         │   │  │ (10 LLM calls)    │  │   │         │       │
│  │         │   │  └───────────────────┘  │   │         │       │
│  └─────────┘   └─────────────────────────┘   └─────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

- Each node = 1 work unit (can be N LLM calls if wrapping AgentLoop)
- Cycles still handled through graph edge conditions
- 10 turns inside loop = graph traverses 3 nodes (start -> coding -> finish)

### Koog as Degenerate Case

**Key insight**: Koog is a **degenerate case** of our design where every node executes exactly once:

| Aspect | Koog | agent-harness GraphStrategy |
|--------|------|----------------------------|
| Node granularity | 1 node = 1 LLM call | 1 node = N LLM calls (flexible) |
| Cycles | Graph edges only | Graph edges + inner loops |
| Nested loops | Not supported | Supported (AgentLoop inside node) |
| Iteration count | max graph iterations | max graph iterations * max inner turns |

### Why This Validates Two-Axis Model

If graph-defined were "Pattern 9" (a termination pattern), then:
- Koog and our proposal would be competing alternatives
- You'd pick one OR the other

But graph-defined is **Axis 2** (execution definition model), so:
- Both Koog and our proposal use graph-defined execution
- Both STILL USE termination patterns (max iterations = Turn-Limited)
- The difference is node granularity, not paradigm

```
Two-Axis Classification:

                    Axis 2: Execution Definition
                    ┌─────────────┬───────────────────┐
                    │ Imperative  │ Graph-Defined     │
────────────────────┼─────────────┼───────────────────┤
Axis 1:             │             │                   │
Turn-Limited        │ Swarm       │ Koog (max iter)   │
                    │ Gemini CLI  │ Our GraphStrategy │
────────────────────┼─────────────┼───────────────────┤
Finish Tool         │ Aider       │ Koog (finish node)│
                    │             │ LangGraph (END)   │
────────────────────┴─────────────┴───────────────────┘
```

Both Koog and our GraphStrategy occupy the **same cells** in this matrix. The difference is implementation detail (node granularity), not architectural paradigm.

### Implication

This analysis confirms:
1. Graph != Pattern 9 (correct decision)
2. GraphStrategy should NOT implement AgentLoop (correct design)
3. Our loops can be composed INTO graph nodes (correct architecture)
4. Koog is compatible with our approach (potential interop path)

---

## Success Criteria

- [ ] GraphStrategy is separate from AgentLoop hierarchy
- [ ] Existing loops can be wrapped as graph nodes
- [ ] Graph topology failures (stuck node) are distinct from loop failures
- [ ] Two-axis taxonomy is preserved
- [ ] All tests pass

---

## References

- `learnings/TWO-AXIS-TAXONOMY-MODEL.md` - Full model explanation
- `learnings/KOOG-GRAPH-ARCHITECTURE-ANALYSIS.md` - Koog code analysis
- `learnings/AGENT-FAILURE-TAXONOMY.md` - Failure classification
