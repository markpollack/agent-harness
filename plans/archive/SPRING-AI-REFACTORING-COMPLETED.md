# Spring AI Refactoring - Completed Work

**Archived**: 2025-12-20
**Status**: Complete - All loops refactored to use Spring AI directly

This document archives the design decisions and implementation details for the Spring AI integration that was completed in Phase 1-2.

---

## Design Philosophy: Direct Adoption over Adapters

After analysis, the original adapter-heavy approach was unnecessary complexity. Our **unique value** is:
- Loop patterns (TurnLimited, EvaluatorOptimizer, StateMachine)
- Termination strategies (multi-condition detection)
- LoopState tracking (iteration, turn, tokens, cost)
- W&B-lite observability

Our `Generator<I,O>` and `ToolExecutor<C>` interfaces **duplicated** what Spring AI already provides:
- `Generator` ≈ `ChatClient` + `ChatModel`
- `ToolExecutor` ≈ `ToolCallback` + `ToolCallingManager`

**Decision: Delete our abstractions and use Spring AI classes directly in loops.**

---

## Architecture Change

### Before (Adapter-Heavy)
```
harness-api (our interfaces) ←→ harness-spring-ai (adapters) ←→ Spring AI
```

### After (Direct Adoption)
```
harness-core (loops + termination) → directly uses Spring AI classes
```

---

## What Was Deleted vs Kept

### Deleted (duplicated Spring AI)
| Our Interface | Spring AI Equivalent |
|---------------|---------------------|
| `Generator<I, O>` | `ChatClient` |
| `GenerationResult<O>` | `ChatResponse` + `ToolExecutionResult` |
| `ToolExecutor<C>` | `ToolCallback` + `ToolCallingManager` |
| `ToolUse` | `ToolCall` (from AssistantMessage) |
| `ToolResult` | `ToolResponseMessage` |
| `GenerationContext` | `ToolContext` + ChatClient context |

### Kept (our unique value)
| Class | Reason |
|-------|--------|
| `AgentLoop<C, S>` | Core loop abstraction (simplified generics) |
| `LoopState` | State tracking not in Spring AI |
| `TerminationStrategy` | Multi-condition termination not in Spring AI |
| `TerminationStrategies` | Common strategies (maxTurns, timeout, etc.) |
| `TurnLimitedLoop` | Pattern 1 implementation |
| `EvaluatorOptimizerLoop` | Pattern 2 implementation |
| `StateMachineLoop` | Pattern 3 implementation |
| All observability classes | W&B-lite is orthogonal |
| Jury integration | Already uses spring-ai-agents |

---

## Refactored AgentLoop Interface

Simplified generics - removed I/O since we use ChatClient's types directly:

```java
package org.springaicommunity.agents.harness.core.loop;

/**
 * Central abstraction for agentic loop patterns.
 * Uses Spring AI ChatClient directly - no adapters needed.
 *
 * @param <C> Configuration type (e.g., TurnLimitedConfig)
 * @param <S> Summary type (final result)
 */
public interface AgentLoop<C, S> {

    /**
     * Executes the loop with the given prompt and configuration.
     *
     * @param userMessage the user message to process
     * @param config loop configuration
     * @param chatClient the Spring AI ChatClient to use
     * @param tools available tool callbacks
     * @return the loop result
     */
    Mono<LoopResult<S>> execute(
        String userMessage,
        C config,
        ChatClient chatClient,
        List<ToolCallback> tools
    );

    TerminationStrategy terminationStrategy();
    LoopType loopType();
}
```

---

## Refactored TurnLimitedLoop (Example)

Uses Spring AI classes directly - no adapters:

```java
@Override
public Mono<LoopResult<TurnLimitedSummary>> execute(
        String userMessage,
        TurnLimitedConfig config,
        ChatClient chatClient,
        List<ToolCallback> tools) {

    return Mono.fromCallable(() -> {
        var state = LoopState.initial(config.maxTurns());
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));

        while (!shouldTerminate(state, config)) {
            // Use Spring AI ChatClient directly
            var response = chatClient.prompt()
                .messages(messages)
                .tools(tools)
                .advisors(buildAdvisors(state, config))
                .call()
                .chatResponse();

            state = state.incrementTurn()
                .addTokens(extractTokens(response));

            // Check for tool calls
            if (response.hasToolCalls()) {
                // Use Spring AI ToolCallingManager directly
                var toolResult = toolCallingManager.executeToolCalls(
                    new Prompt(messages), response);

                messages.addAll(toolResult.conversationHistory());

                // Check for finish tool
                if (isFinishToolCalled(response, config.finishToolName())) {
                    return buildResult(state, TerminationReason.FINISH_TOOL_CALLED);
                }
            } else {
                messages.add(response.getResult().getOutput());
                break;
            }
        }

        return buildResult(state, determineTerminationReason(state, config));
    });
}
```

---

## Files Modified/Deleted

### Deleted
- `harness-api/src/.../generator/Generator.java`
- `harness-api/src/.../generator/GenerationContext.java`
- `harness-api/src/.../core/ToolExecutor.java`
- `harness-api/src/.../core/ToolDrivenLoop.java`

### Modified
- `harness-api/src/.../core/AgentLoop.java` → Simplified generics
- `harness-patterns/src/.../TurnLimitedLoop.java` → Uses ChatClient
- `harness-patterns/src/.../EvaluatorOptimizerLoop.java` → Uses ChatClient
- `harness-patterns/src/.../StateMachineLoop.java` → Uses ChatClient

---

## Key Insight

Spring AI provides the **infrastructure** (ChatClient, ToolCallback, Advisors).
We provide the **patterns** (loop orchestration, termination logic, state tracking).

These are **complementary**, not competing. By adopting Spring AI's domain classes:
1. We eliminate adapter code
2. MCP tools work automatically
3. Tool search/augmenter integrate naturally
4. Memory works through standard advisors
5. Users get familiar Spring AI APIs

Our value is the **loop patterns** themselves, not the interfaces they use.
