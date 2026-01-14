# Learning: Koog Graph-Based Architecture Analysis

**Date**: 2026-01-14
**Source**: Direct code analysis of [JetBrains Koog](https://github.com/JetBrains/koog)
**Context**: Understanding graph-based vs loop-based agent paradigms

---

## Executive Summary

Koog represents a **graph-defined loop** paradigm—not a loop-free DAG nor a pure imperative loop. The graph DEFINES possible execution paths, but the EXECUTION is still a while loop traversing the graph until a terminal node is reached.

---

## Architecture Overview

### Three-Layer Structure

```
┌─────────────────────────────────────────────────────────────────┐
│  DECLARATIVE LAYER (Graph Definition)                           │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │   Nodes     │───>│   Edges     │───>│  Strategy   │         │
│  │ (work unit) │    │ (routing)   │    │ (full graph)│         │
│  └─────────────┘    └─────────────┘    └─────────────┘         │
├─────────────────────────────────────────────────────────────────┤
│  EXECUTION LAYER (Graph Traversal)                              │
│  while (true) {                                                 │
│    nodeOutput = currentNode.execute()                           │
│    edge = currentNode.resolveEdge(nodeOutput)                  │
│    if (edge == null && currentNode == finish) break            │
│    currentNode = edge.toNode                                    │
│  }                                                              │
├─────────────────────────────────────────────────────────────────┤
│  STATE LAYER (Context & Checkpointing)                          │
│  - Message history                                              │
│  - Storage (key-value)                                          │
│  - Execution point (current node + input)                       │
│  - Iteration counter                                            │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Code Analysis

### Node Definition (`AIAgentNode.kt`)

```kotlin
public abstract class AIAgentNodeBase<TInput, TOutput> {
    public abstract val name: String
    public var edges: List<AIAgentEdge<TOutput, *>> = emptyList()

    public abstract suspend fun execute(
        context: AIAgentGraphContextBase,
        input: TInput
    ): TOutput?

    public suspend fun resolveEdge(
        context: AIAgentGraphContextBase,
        nodeOutput: TOutput
    ): ResolvedEdge? {
        for (currentEdge in edges) {
            val output = currentEdge.forwardOutputUnsafe(nodeOutput, context)
            if (!output.isEmpty) {
                return ResolvedEdge(currentEdge, output.value)
            }
        }
        return null
    }
}
```

**Key insight**: Edges are evaluated in order, first matching edge wins.

### Edge Definition (`AIAgentEdge.kt`)

```kotlin
public class AIAgentEdge<IncomingOutput, OutgoingInput>(
    public val toNode: AIAgentNodeBase<OutgoingInput, *>,
    internal val forwardOutput: suspend (
        context: AIAgentGraphContextBase,
        output: IncomingOutput
    ) -> Option<OutgoingInput>,
)
```

**Key insight**: Edges have a **condition function** (`forwardOutput`) that returns `Option.Some` to route or `Option.None` to skip.

### Graph Execution Loop (`AIAgentSubgraph.kt:242-278`)

```kotlin
while (true) {
    // Safety limit check
    context.stateManager.withStateLock { state ->
        if (++state.iterations > context.config.maxAgentIterations) {
            throw AIAgentMaxNumberOfIterationsReachedException(...)
        }
    }

    // Execute current node
    val nodeOutput: Any? = currentNode.executeUnsafe(context, currentInput)

    // Check for interrupt/rollback
    if (context.getAgentContextData() != null) {
        return null
    }

    // Find next edge
    val resolvedEdge = currentNode.resolveEdgeUnsafe(context, nodeOutput)
        ?: if (currentNode == finish) {
            currentInput = nodeOutput
            break  // TERMINATION: Reached finish node
        } else {
            throw AIAgentStuckInTheNodeException(currentNode, nodeOutput)
        }

    // Move to next node
    currentNode = resolvedEdge.edge.toNode
    currentInput = resolvedEdge.output
}
```

---

## Pre-Built Strategies

### 1. Chat Agent Strategy (`chatAgentStrategy`)

```
Start → CallLLM → ExecuteTool → SendToolResult → Finish
                ↑               ↓
                └───────────────┘ (tool call loop)
```

### 2. ReAct Strategy (`reActStrategy`)

```
Start → Setup → CallLLM(Reason) → CallLLM(Action) → Finish
                      ↑                   ↓
                      └─── ExecuteTool ───┘
```

### 3. Single Run Strategy

```
Start → CallLLM → ExecuteTools → SendResults → Finish
```

---

## Termination Mechanisms

| Mechanism | Location | Trigger |
|-----------|----------|---------|
| **FinishNode reached** | `AIAgentSubgraph.kt:268` | No edges from finish node |
| **Max iterations** | `AIAgentSubgraph.kt:244-251` | `context.config.maxAgentIterations` exceeded |
| **Stuck in node** | `AIAgentSubgraph.kt:272-273` | No valid edge from non-finish node |
| **Interrupt/Rollback** | `AIAgentSubgraph.kt:261-263` | `context.getAgentContextData() != null` |

---

## Graph DSL Example

```kotlin
// Define a ReAct-style strategy
val strategy = strategy<String, String>("react") {
    // Declare nodes
    val nodeCallLLM by nodeLLMRequest("sendInput")
    val nodeExecuteTool by nodeExecuteTool("executeTool")
    val nodeSendToolResult by nodeLLMSendToolResult("sendResult")

    // Define edges with conditions
    edge(nodeStart forwardTo nodeCallLLM)

    edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
    edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })

    edge(nodeExecuteTool forwardTo nodeSendToolResult)

    edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
    edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
}
```

---

## Comparison: Graph-Defined vs Pure Loop

| Aspect | Koog (Graph-Defined) | Claude Code (Pure Loop) |
|--------|---------------------|-------------------------|
| **Definition** | Declarative graph DSL | Imperative while loop |
| **Routing** | Edge conditions | If/else in loop body |
| **Checkpointing** | Serialize current node | Manual state save |
| **Visualization** | Graph is self-documenting | Read code |
| **Composability** | Subgraphs nest naturally | Call other functions |
| **Execution** | While loop over graph | Direct while loop |
| **Termination** | Reach FinishNode OR max iterations | Condition in while |

### Key Realization

**Both are fundamentally loops**. The difference is:
- **Koog**: Loop traverses a pre-defined graph structure
- **Claude Code**: Loop contains inline decision logic

---

## Implications for agent-harness

### What Koog Provides That We Don't

1. **Graph Definition DSL**: Declarative workflow definition
2. **Built-in Checkpointing**: Serialize execution point
3. **Subgraph Composition**: Nest graphs naturally
4. **Edge Conditions**: Explicit routing logic

### What We Provide That Koog Doesn't

1. **Judge Abstraction**: Scored evaluation (not just boolean)
2. **Evaluator-Optimizer Pattern**: Explicit refinement loop
3. **Multiple Termination Strategies**: Beyond max iterations

### Architectural Options

| Option | Description | Effort |
|--------|-------------|--------|
| **A: Ignore** | Stay loop-first, graph is separate paradigm | None |
| **B: Bridge** | Our loops can be nodes in a graph | Medium |
| **C: Adopt** | Add graph DSL to agent-harness | High |

**Recommendation**: Option A or B. Graph-based is a higher-level orchestration pattern. Our loops can be **nodes inside** a Koog/LangGraph graph.

---

## Classification Update

Our taxonomy should acknowledge the paradigm distinction:

| Paradigm | Examples | Execution Model |
|----------|----------|-----------------|
| **Loop-First** | Claude Code, Gemini CLI, Swarm | Imperative while loop |
| **Graph-Defined** | Koog, LangGraph | While loop over graph |
| **Pure DAG** | Airflow, Prefect | Topological traversal |

Our 8 patterns apply to **Loop-First** and are compatible with **Graph-Defined** (as node implementations).

---

## References

- Source: `/tmp/koog/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/`
- Key files analyzed:
  - `agent/entity/AIAgentNode.kt` - Node abstraction
  - `agent/entity/AIAgentEdge.kt` - Edge with conditions
  - `agent/entity/AIAgentSubgraph.kt` - Execution loop (lines 242-278)
  - `agent/entity/AIAgentGraphStrategy.kt` - Top-level strategy
  - `dsl/builder/AIAgentGraphStrategyBuilder.kt` - DSL builder
- Pre-built strategies: `/tmp/koog/agents/agents-ext/src/commonMain/kotlin/ai/koog/agents/ext/agent/AIAgentStrategies.kt`

---

*This analysis clarifies that Koog's "graph-based" approach is a graph-defined loop, not fundamentally different from loop-based agents—just a higher-level abstraction with better composability and checkpointing.*
