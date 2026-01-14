# Learning: Graph Design Validation from Source Code Analysis

**Date**: 2026-01-14
**Context**: Validating agent-harness GraphStrategy design against production frameworks
**Sources**: JGraphT, LangGraph4j, LangGraph (Python), Koog (Kotlin), LlamaIndex Workflows

---

## Executive Summary

We analyzed source code from 5 major frameworks to validate our GraphStrategy design. The analysis confirms our approach while revealing patterns to adopt and avoid.

---

## Source Code Repositories Analyzed

Cloned to `plans/supporting_repos/`:

| Repository | Path | Relevance |
|------------|------|-----------|
| [LangGraph4j](https://github.com/langgraph4j/langgraph4j) | `langgraph4j/` | Java port of LangGraph, directly comparable |
| [JGraphT](https://github.com/jgrapht/jgrapht) | `jgrapht/` | Traditional Java graph patterns |
| [LangGraph](https://github.com/langchain-ai/langgraph) | `langgraph/` | Original Python implementation |
| [Koog](https://github.com/JetBrains/koog) | `koog/` | JetBrains Kotlin agent framework, graph-defined loops |

---

## Pattern Analysis

### 1. JGraphT (Traditional Graph Library)

**Source**: `jgrapht/jgrapht-core/src/main/java/org/jgrapht/Graph.java`

```java
public interface Graph<V, E> {
    Set<E> getAllEdges(V sourceVertex, V targetVertex);
    E addEdge(V sourceVertex, V targetVertex);
    boolean addVertex(V v);
    Set<E> edgeSet();
    Set<V> vertexSet();
    // ... more methods
}
```

**Key Patterns**:
- Generic type parameters `<V, E>` for vertices and edges
- Supplier pattern for vertex/edge creation
- Mutable graph - add/remove at runtime
- No execution semantics - pure data structure

**Applicability to agent-harness**: LOW
- JGraphT is a data structure library, not an execution framework
- We need nodes that DO work, not just store data
- Edge conditions need to be evaluated at runtime

---

### 2. LangGraph4j (Java Port - Most Relevant)

**Source**: `langgraph4j/langgraph4j-core/src/main/java/org/bsc/langgraph4j/`

#### Node Definition

```java
// NodeAction.java
@FunctionalInterface
public interface NodeAction<T extends AgentState> {
    Map<String, Object> apply(T state) throws Exception;
}
```

**Key insight**: Nodes are functions `State -> Map<String, Object>` that return state updates.

#### Edge Definition

```java
// EdgeAction.java
@FunctionalInterface
public interface EdgeAction<S extends AgentState> {
    String apply(S state) throws Exception;  // Returns next node name
}
```

**Key insight**: Conditional edges return the NAME of the next node as a String.

#### StateGraph Builder

```java
// StateGraph.java (simplified)
public class StateGraph<State extends AgentState> {
    final Nodes<State> nodes = new Nodes<>();
    final Edges<State> edges = new Edges<>();

    public StateGraph<State> addNode(String id, AsyncNodeAction<State> action);
    public StateGraph<State> addEdge(String sourceId, String targetId);
    public StateGraph<State> addConditionalEdges(String sourceId,
        AsyncEdgeAction<State> condition, Map<String, String> mappings);

    public CompiledGraph<State> compile();  // Must compile before execution
}
```

**Key Patterns**:
1. **Builder pattern** - accumulate nodes/edges, then compile
2. **Compile-then-execute** - validation at compile time
3. **String-based node references** - edges reference nodes by name
4. **State as Map** - nodes receive/return `Map<String, Object>`
5. **Edge condition mappings** - `Map<String, String>` maps condition results to node names

**Subgraph Composition**:

```java
public StateGraph<State> addNode(String id, CompiledGraph<State> subGraph);
public StateGraph<State> addNode(String id, StateGraph<State> subGraph);
```

**Key insight**: Subgraphs are added AS NODES, not as separate entities.

---

### 3. LangGraph Python (Original)

**Source**: `langgraph/libs/langgraph/langgraph/graph/state.py`

```python
class StateGraph(Generic[StateT, ContextT, InputT, OutputT]):
    edges: set[tuple[str, str]]
    nodes: dict[str, StateNodeSpec[Any, ContextT]]
    branches: defaultdict[str, dict[str, BranchSpec]]
    channels: dict[str, BaseChannel]

    def add_node(self, name: str, node: StateNode) -> Self: ...
    def add_edge(self, start: str, end: str) -> Self: ...
    def compile(self) -> CompiledStateGraph: ...
```

**Key Patterns**:
1. **Generic type parameters** - `StateT`, `ContextT`, `InputT`, `OutputT`
2. **Channels for state** - reducers aggregate state updates
3. **Same compile pattern** - builder then compile
4. **Context separate from State** - immutable context vs mutable state

---

### 4. Koog (JetBrains Kotlin - Most Detailed Graph Implementation)

**Source**: `koog/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/`

#### Node Definition

```kotlin
// AIAgentNode.kt
public abstract class AIAgentNodeBase<TInput, TOutput> {
    public abstract val name: String
    public var edges: List<AIAgentEdge<TOutput, *>> = emptyList()

    public abstract suspend fun execute(context: AIAgentGraphContextBase, input: TInput): TOutput?

    public suspend fun resolveEdge(context: AIAgentGraphContextBase, nodeOutput: TOutput): ResolvedEdge? {
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

**Key insights**:
- Nodes have a `name` identifier (String)
- Nodes own their outgoing `edges` list
- `resolveEdge()` iterates edges, first matching wins
- Generic `<TInput, TOutput>` for type safety

#### Edge Definition

```kotlin
// AIAgentEdge.kt
public class AIAgentEdge<IncomingOutput, OutgoingInput>(
    public val toNode: AIAgentNodeBase<OutgoingInput, *>,
    internal val forwardOutput: suspend (context, output) -> Option<OutgoingInput>,
)
```

**Key insights**:
- Edge has direct reference to `toNode` (not string ID)
- `forwardOutput` is a condition function returning `Option`
- `Option.Some` = edge matches, `Option.None` = try next edge

#### Execution Loop (Critical Pattern)

```kotlin
// AIAgentSubgraph.kt (lines 236-272)
while (true) {
    // Safety limit check
    context.stateManager.withStateLock { state ->
        if (++state.iterations > context.config.maxAgentIterations) {
            throw AIAgentMaxNumberOfIterationsReachedException(limit)
        }
    }

    // Execute current node
    val nodeOutput: Any? = currentNode.executeUnsafe(context, currentInput)

    // Find next edge
    val resolvedEdge = currentNode.resolveEdgeUnsafe(context, nodeOutput)
        ?: if (currentNode == finish) {
            break  // TERMINATION: Reached finish node
        } else {
            throw AIAgentStuckInTheNodeException(currentNode, nodeOutput)
        }

    // Move to next node
    currentNode = resolvedEdge.edge.toNode
    currentInput = resolvedEdge.output
}
```

**Key insights**:
1. **While loop traversal** - graph-defined but still a while loop
2. **Max iterations safety** - `maxAgentIterations` config
3. **Stuck node detection** - throws if no edge from non-finish node
4. **Finish node termination** - `break` when reaching finish with no edges

#### Special Nodes

```kotlin
// StartNode and FinishNode
public class StartNode<TInput> : AIAgentNode<TInput, TInput>(
    name = "__start__",
    execute = { input -> input }  // Pass-through
)

public class FinishNode<TOutput> : AIAgentNode<TOutput, TOutput>(
    name = "__finish__",
    execute = { input -> input }  // Pass-through
) {
    override fun addEdge(edge: AIAgentEdge<TOutput, *>) {
        throw IllegalStateException("FinishNode cannot have outgoing edges")
    }
}
```

**Key insights**:
- Start/Finish are special nodes with reserved names
- FinishNode PREVENTS edge addition at compile time
- Both are pass-through (input = output)

#### Subgraph Composition

```kotlin
// AIAgentSubgraph extends AIAgentNodeBase
public open class AIAgentSubgraph<TInput, TOutput>(
    override val name: String,
    public val start: StartNode<TInput>,
    public val finish: FinishNode<TOutput>,
    // ...
) : AIAgentNodeBase<TInput, TOutput>()
```

**Key insight**: `AIAgentSubgraph` IS a node. Subgraphs can be composed into larger graphs.

**Applicability to agent-harness**: HIGH
- Same pattern we're proposing: graph node can contain a loop
- Koog uses fine-grained nodes (1 LLM call), we allow coarse-grained
- Exception-based stuck detection matches our failure taxonomy

---

### 5. LlamaIndex Workflows (Alternative Pattern)

**Source**: Documentation analysis (event-driven architecture)

```python
class Workflow:
    @step
    async def step_a(self, ev: StartEvent) -> Event1:
        return Event1(data="...")

    @step
    async def step_b(self, ev: Event1) -> StopEvent:
        return StopEvent(result="...")
```

**Key Insight**: LlamaIndex REJECTED graphs in favor of events:

> "Other frameworks have attempted to solve this with DAGs but these have limitations: Logic like loops and branches needed to be encoded into edges, which made them hard to read and understand."

**Applicability**: We're not adopting event-driven, but the criticism is valid - our edge conditions should be simple.

---

## Design Decisions Validated

### 1. GraphStrategy as Composition Layer (CONFIRMED)

LangGraph4j's `addNode(String id, CompiledGraph<State> subGraph)` confirms our approach:
- Subgraphs are nodes, not separate abstractions
- Our `LoopGraphNode` wrapping `AgentLoop` follows this pattern

### 2. Builder Pattern with Compile (CONFIRMED)

All frameworks use compile-then-execute:
- LangGraph4j: `StateGraph.compile() -> CompiledGraph`
- LangGraph: `StateGraph.compile() -> CompiledStateGraph`

**Our approach**: `GraphStrategyBuilder.build() -> GraphStrategy` (equivalent)

### 3. String-Based Node References (ADOPT)

LangGraph4j uses strings for node IDs in edge definitions:

```java
.addEdge("node1", "node2")
.addConditionalEdges("router", condition, Map.of("a", "nodeA", "b", "nodeB"))
```

**Recommendation**: Use string IDs for edges, not direct object references. This enables:
- Late binding (nodes added after edges)
- Simpler serialization
- Better error messages

### 4. State vs Context Separation (CONSIDER)

LangGraph Python separates:
- `StateT` - mutable, passed between nodes
- `ContextT` - immutable, runtime configuration

**Our approach**: `GraphContext` combines both. Consider splitting in future.

---

## Patterns to ADOPT in agent-harness

| Pattern | Source | How to Adopt |
|---------|--------|--------------|
| String node IDs | LangGraph4j | Use `String name` for node identification |
| Compile-time validation | All | Validate graph structure in `build()` |
| Edge condition as function | LangGraph4j, Koog | Condition returns next node or `Option` |
| Subgraph as node | LangGraph4j, Koog | `LoopGraphNode` wraps AgentLoop |
| Error enum | LangGraph4j | `GraphStateException` with typed errors |
| Start/Finish special nodes | Koog | Reserved `__start__`/`__finish__` names |
| Stuck node exception | Koog | `GraphStuckInNodeException` for topology failure |
| Max iterations limit | Koog | Safety guard in execution loop |
| Edge list on node | Koog | Nodes own their outgoing edges |

---

## Patterns to AVOID

| Pattern | Why Avoid |
|---------|-----------|
| JGraphT's mutable graph | We need immutable after build |
| Complex generic signatures | `StateGraph<StateT, ContextT, InputT, OutputT>` is hard to use |
| String-only state | Java benefits from typed state, not just `Map<String,Object>` |
| Implicit edge resolution | Explicit is better than implicit |

---

## Updated Design Recommendations

### 1. Use String Node IDs

```java
// Instead of
.edge(nodeA).to(nodeB)

// Use
.edge("start").to("plan")
.edge("test").to("finish").when(result -> result.passed())
.edge("test").to("code").when(result -> !result.passed())
```

### 2. Add Graph Validation

```java
// In GraphStrategyBuilder.build()
private void validateGraph() {
    // Check all edge targets exist
    // Check start node exists
    // Check finish node exists
    // Check for unreachable nodes
}
```

### 3. Consider Typed Error Enum (from LangGraph4j)

```java
public enum GraphError {
    INVALID_NODE_ID("[%s] is not a valid node id!"),
    DUPLICATE_NODE("node with id: %s already exists!"),
    MISSING_EDGE_TARGET("edge target '%s' doesn't exist!"),
    // ...
}
```

---

## Relationship to Existing Patterns

| Framework Pattern | agent-harness Equivalent |
|-------------------|-------------------------|
| LangGraph4j `NodeAction<S>` | `GraphNode<I,O>.execute()` |
| LangGraph4j `EdgeAction<S>` | `GraphEdge.condition()` |
| LangGraph4j `StateGraph` | `GraphStrategyBuilder` |
| LangGraph4j `CompiledGraph` | `GraphStrategy` |
| LangGraph4j `addNode(id, subGraph)` | `loopNode(name, AgentLoop)` |
| Koog `AIAgentNodeBase<I,O>` | `GraphNode<I,O>` |
| Koog `AIAgentEdge<In,Out>` | `GraphEdge<T>` |
| Koog `AIAgentSubgraph` | `GraphStrategy` (graph as node) |
| Koog `StartNode`/`FinishNode` | Implicit start/finish nodes |
| Koog `AIAgentStuckInTheNodeException` | `GraphResult.stuckInNode()` |
| Koog `maxAgentIterations` | `GraphStrategy.maxIterations` |

---

## Conclusion

Our design is validated by production frameworks. Key confirmations:
1. Graph as composition layer, not execution pattern - matches LangGraph4j, Koog
2. Builder pattern with compile/build - universal pattern
3. Subgraph/loop as node - matches `addNode(id, CompiledGraph)` and Koog's `AIAgentSubgraph`
4. String-based node references - simpler than object references
5. Stuck node detection - Koog validates our failure taxonomy
6. Max iterations safety - universal pattern across all frameworks

**Koog provides the most detailed reference** for our execution loop implementation:
- While loop over graph nodes
- Edge resolution with first-match semantics
- Exception for stuck nodes (non-finish without valid edge)
- Finish node detection for termination

**Proceed with implementation** using:
- String node IDs
- Compile-time validation
- Stuck node exception
- Max iterations safety limit

---

## References

- LangGraph4j: `plans/supporting_repos/langgraph4j/langgraph4j-core/src/main/java/org/bsc/langgraph4j/StateGraph.java`
- JGraphT: `plans/supporting_repos/jgrapht/jgrapht-core/src/main/java/org/jgrapht/Graph.java`
- LangGraph Python: `plans/supporting_repos/langgraph/libs/langgraph/langgraph/graph/state.py`
- Koog Node: `plans/supporting_repos/koog/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/AIAgentNode.kt`
- Koog Edge: `plans/supporting_repos/koog/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/AIAgentEdge.kt`
- Koog Subgraph: `plans/supporting_repos/koog/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/entity/AIAgentSubgraph.kt`

---

*This analysis validates our GraphStrategy design and provides patterns to adopt from production frameworks.*
