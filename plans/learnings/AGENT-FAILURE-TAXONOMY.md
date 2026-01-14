# Learning: Agent Failure Taxonomy

**Date**: 2026-01-14
**Context**: Formalizing failure modes across loop-first and graph-defined agent architectures

---

## Executive Summary

Agent loops can fail in distinct ways that require different detection and recovery strategies. Graph-defined execution surfaces a new failure class ("stuck node") not present in pure imperative loops.

---

## Failure Taxonomy

### Class 1: Resource Exhaustion

Failures caused by hitting predefined limits.

| Failure | Trigger | Detection | Recovery |
|---------|---------|-----------|----------|
| **Max turns exceeded** | `turns >= maxTurns` | Counter check | Increase limit, summarize context, or fail gracefully |
| **Timeout** | `elapsed >= timeout` | Clock/timer | Extend timeout, checkpoint and resume later |
| **Token limit** | Context window full | Token counter | Compress history, summarize, or start fresh |
| **Memory exhaustion** | OOM | System signal | Reduce batch size, stream instead of buffer |

**Code pattern (Koog):**
```kotlin
if (++state.iterations > context.config.maxAgentIterations) {
    throw AIAgentMaxNumberOfIterationsReachedException(limit)
}
```

**Code pattern (Gemini CLI):**
```typescript
if (turns >= maxTurns) {
    return { status: 'max_turns_exceeded' };
}
```

---

### Class 2: Semantic Deadlock

Failures where the agent is "stuck" logically, not resource-limited.

| Failure | Trigger | Detection | Recovery |
|---------|---------|-----------|----------|
| **Repetitive action** | Same tool call N times | Action history comparison | Inject prompt: "You've tried X, try something different" |
| **Empty response** | LLM returns no action | Response parsing | Re-prompt with explicit instruction |
| **Circular reasoning** | Agent returns to same state | State hash tracking | Force different branch or escalate |

**Academic evidence** (ReAct paper):
> "47% of errors stemmed from repetitive action sequences where the agent called the same API repeatedly."

**Detection strategy:**
```java
// Track last N actions
if (actionHistory.lastN(3).allEqual()) {
    throw SemanticDeadlockException("Repetitive action detected");
}
```

---

### Class 3: Graph Topology (NEW - Graph-Defined Only)

Failures specific to graph-defined execution where the graph structure prevents progress.

| Failure | Trigger | Detection | Recovery |
|---------|---------|-----------|----------|
| **Stuck node** | No valid edge from non-terminal node | Edge resolution returns null | Redesign graph, add fallback edge |
| **Unreachable terminal** | No path to finish node | Static analysis or runtime | Add missing edges |
| **Infinite cycle** | Cycle with no exit condition | Iteration + state tracking | Add cycle-breaking condition |

**Code pattern (Koog):**
```kotlin
val resolvedEdge = currentNode.resolveEdgeUnsafe(context, nodeOutput)
    ?: if (currentNode == finish) {
        break  // OK: reached terminal
    } else {
        throw AIAgentStuckInTheNodeException(currentNode, nodeOutput)  // FAILURE
    }
```

**Why this is distinct:**
- Not resource exhaustion (agent has turns remaining)
- Not semantic deadlock (agent isn't repeating)
- The **graph definition** is incomplete or incorrect

**Prevention:**
```kotlin
// Static analysis at graph build time
fun validateGraph(strategy: AIAgentGraphStrategy) {
    for (node in strategy.allNodes()) {
        if (node != strategy.finish && node.edges.isEmpty()) {
            throw InvalidGraphException("Node ${node.name} has no outgoing edges")
        }
    }
}
```

---

### Class 4: Tool Failure

Failures in external tool execution.

| Failure | Trigger | Detection | Recovery |
|---------|---------|-----------|----------|
| **Tool timeout** | External API slow | Timeout exception | Retry with backoff, use fallback |
| **Tool error** | API returns error | Error response parsing | Retry, skip, or report to LLM |
| **Tool not found** | Invalid tool name | Registry lookup | Report available tools to LLM |
| **Invalid arguments** | Schema mismatch | Validation | Report schema to LLM for correction |

**Recovery pattern:**
```java
try {
    result = tool.execute(args);
} catch (ToolException e) {
    // Let LLM know what went wrong
    return ToolResult.error(e.getMessage());
}
```

---

### Class 5: Model Behavior

Failures originating from the LLM itself.

| Failure | Trigger | Detection | Recovery |
|---------|---------|-----------|----------|
| **Safety refusal** | Content filter triggered | Refusal message pattern | Rephrase request, escalate to human |
| **Capability limit** | Task beyond model ability | Repeated failures on same task | Use different model, decompose task |
| **Hallucination** | Invented tool/fact | Validation against ground truth | Verify outputs, use RAG |
| **Format violation** | Invalid JSON, wrong schema | Parse error | Retry with format reminder |

**Detection pattern:**
```java
if (response.contains("I cannot") || response.contains("I'm unable")) {
    throw ModelRefusalException(response);
}
```

---

## Failure Matrix by Architecture

| Failure Class | Imperative Loop | Graph-Defined | Notes |
|---------------|-----------------|---------------|-------|
| Resource exhaustion | ✓ | ✓ | Universal |
| Semantic deadlock | ✓ | ✓ | Universal |
| Graph topology | ✗ | ✓ | **Graph-specific** |
| Tool failure | ✓ | ✓ | Universal |
| Model behavior | ✓ | ✓ | Universal |

---

## Detection Strategy Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                    FAILURE DETECTION LAYERS                      │
├─────────────────────────────────────────────────────────────────┤
│  Layer 1: Resource Guards (before each iteration)               │
│  - Check turn count                                              │
│  - Check elapsed time                                            │
│  - Check token budget                                            │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: Semantic Analysis (after LLM response)                │
│  - Compare to action history                                     │
│  - Detect empty/invalid responses                                │
│  - Track state for cycles                                        │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: Graph Validation (graph-defined only)                 │
│  - Static: Check all nodes have paths to terminal               │
│  - Runtime: Detect stuck nodes                                   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4: Tool Execution (during tool calls)                    │
│  - Timeout handling                                              │
│  - Error response handling                                       │
│  - Argument validation                                           │
├─────────────────────────────────────────────────────────────────┤
│  Layer 5: Model Output (after each LLM call)                    │
│  - Refusal detection                                             │
│  - Format validation                                             │
│  - Hallucination checks                                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Implications for agent-harness

### Current Coverage

| Failure Class | agent-harness Support | Notes |
|---------------|----------------------|-------|
| Resource exhaustion | ✓ `maxTurns`, timeout | Core feature |
| Semantic deadlock | Partial | Could add action history tracking |
| Graph topology | ✗ | Not applicable (loop-first) |
| Tool failure | ✓ Via Spring AI | Delegated to framework |
| Model behavior | Partial | Could add refusal detection |

### Recommended Additions

1. **Action History Tracking** - Detect repetitive actions
2. **Refusal Detection** - Recognize model safety refusals
3. **Failure Callbacks** - Let users handle specific failure types

```java
// Proposed API
TurnLimitedLoop.builder()
    .maxTurns(10)
    .onMaxTurnsExceeded(ctx -> summarizeAndContinue(ctx))
    .onSemanticDeadlock(ctx -> injectVariation(ctx))
    .onModelRefusal(ctx -> escalateToHuman(ctx))
    .build();
```

---

## References

- Koog stuck node: `/tmp/koog/agents/agents-core/.../AIAgentSubgraph.kt:272`
- ReAct failure analysis: Yao et al. 2022, Section 4.2
- Gemini CLI max turns: `/tmp/gemini-cli/packages/core/src/agents/executor.ts:394`

---

*This taxonomy provides a foundation for comprehensive failure handling across agent architectures.*
