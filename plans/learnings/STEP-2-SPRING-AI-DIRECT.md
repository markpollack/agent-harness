# STEP 2 Learnings: Spring AI Direct Integration

**Completed**: 2025-12-19
**Updated**: 2025-12-21 (Reactor removal, bounded type parameter)

## Summary
Refactored all loop patterns to use Spring AI ChatClient directly, eliminating adapter layer.

## Key Decisions

### 1. Delete vs Keep
We deleted interfaces that duplicated Spring AI:
- `Generator<I, O>` → use `ChatClient`
- `ToolExecutor<C>` → use `ToolCallback` + `ToolCallingManager`
- `GenerationContext` → use `ToolContext`

We kept interfaces that provide unique value:
- `AgentLoop<C, S>` - loop orchestration
- `LoopState` - state tracking (turns, tokens, cost)
- `TerminationStrategy` - multi-condition termination

### 2. Simplified Generics (Two Phases)

**Phase 1**: Changed from `AgentLoop<I, O, C, S>` to `AgentLoop<C, S>`:
- Removed I/O since ChatClient handles message types
- C = configuration (e.g., TurnLimitedConfig)
- S = summary (final result type)

**Phase 2**: Changed from `AgentLoop<S>` to `AgentLoop<R extends LoopResult>`:
- Removed configuration generic (config at construction time)
- R = pattern-specific result type (e.g., `TurnLimitedResult`)
- Common contract via `LoopResult` interface
- See `design/agentloop-result-types.md` for full design

### 3. ChatClient Usage Pattern
```java
ChatResponse response = chatClient.prompt()
    .user(userMessage)
    .tools((Object[]) tools.toArray(new ToolCallback[0]))
    .call()
    .chatResponse();
```

Note the cast to `(Object[])` - required to avoid varargs warning.

### 4. Tool Calling
ChatClient handles tool execution automatically when tools are provided.
We check for finish tool by inspecting `response.getResult().getOutput().getToolCalls()`.

## Issues Encountered

### Varargs Warning
**Problem**: `chatClient.tools(tools.toArray())` caused unchecked varargs warning.
**Solution**: Cast to `(Object[]) tools.toArray(new ToolCallback[0])`.

### LoopState Generics
**Problem**: LoopState had output type parameter that was no longer needed.
**Solution**: Removed generic, LoopState is now metrics-only (turn, tokens, cost, stuck detection).

## Dependencies Added
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-client-chat</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

## Files Modified
- `AgentLoop.java` - simplified generics
- `TurnLimitedLoop.java` - uses ChatClient directly
- `EvaluatorOptimizerLoop.java` - uses ChatClient directly
- `StateMachineLoop.java` - uses ChatClient directly
- `JuryTerminationStrategy.java` - removed generic
- `SpringAiJuryAdapter.java` - takes ChatResponse

## Reactor Removal (2025-12-20)

### Rationale
Agent loops are inherently **sequential** (turn → tool → turn). Reactor provides no benefit:
- No backpressure needed (one turn at a time)
- No composition benefits (simple while loop suffices)
- Forces users to call `.block()` or deal with `Mono<>`

With Java 21 virtual threads, we get non-blocking I/O without reactive complexity.

### Changes Made
| File | Before | After |
|------|--------|-------|
| `AgentLoop.execute()` | `Mono<LoopResult<S>>` | `LoopResult<S>` |
| `TurnLimitedLoop` | `.expand()` reactive chain | Simple `while(true)` loop |
| `EvaluatorOptimizerLoop` | `Mono.fromCallable()` | Direct method |
| `StateMachineLoop` | `Mono.fromCallable()` | Direct method |
| `SpringAiJuryAdapter.evaluate()` | `Mono<Verdict>` | `Verdict` |

### Pattern
Loops now use simple control flow:
```java
while (true) {
    TurnResult result = executeTurn(...);
    if (result.terminated) {
        return buildResult(result);
    }
}
```

## Bounded Type Parameter Refactoring (2025-12-21)

### What Changed
| Before | After |
|--------|-------|
| `AgentLoop<S>` | `AgentLoop<R extends LoopResult>` |
| `SummaryBuilder<S>` parameter | Returns result directly |
| Generic summary type | Pattern-specific result record |

### New Core Types
- `LoopResult` - Common interface for all loop results
- `LoopStatus` - Enum: `RUNNING`, `COMPLETED`, `STOPPED`, `FAILED`, `ERROR`
- `TerminationReason` - Enum with 14 termination reasons

### Pattern-Specific Results
| Pattern | Result Type | Pattern-Specific Fields |
|---------|-------------|------------------------|
| TurnLimited | `TurnLimitedResult` | `finalState`, `lastVerdict` |
| EvaluatorOptimizer | `EvaluatorOptimizerResult` | `trials`, `bestScore`, `bestReflection` |
| StateMachine | `StateMachineResult` | `transitions`, `finalState`, `finalAttributes` |

### Benefits
1. No more `SummaryBuilder` boilerplate
2. Type-safe access to pattern-specific data
3. Common query methods (`isSuccess()`, `isFailure()`)
4. Extensible for user-defined loops

## Next Step Reference
For Step 3 (Records and Builders), key patterns from this step:
1. Config records will use Spring-style builders
2. Result records will use static factory methods
3. See `design/records-and-builders.md` for builder patterns

For Step 4 (MiniAgent), key patterns from this step:
1. Tools passed as `List<ToolCallback>` to loop
2. Memory can be added via `MessageChatMemoryAdvisor`
3. Finish tool detection via tool call name matching
4. All APIs are synchronous - simple `loop.execute()` call
