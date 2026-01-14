# Step 4.6: MiniAgent Governance Design

**Status**: ✅ Implemented
**Problem**: MiniAgent doesn't use our harness abstractions or enforce turn limits
**Decision**: Option B - Subclass ToolCallAdvisor for turn limiting

---

## The Smell

MiniAgent was created as a "100-line agent" to beat Python mini-swe-agent in conciseness. It achieved this by delegating entirely to Spring AI's `ChatClient` + `ToolCallAdvisor`.

**The problem**: It's our "first trivial agent loop" but:
1. Doesn't use our own abstractions (`TurnLimitedLoop`, `AgentLoop`, etc.)
2. Has `maxTurns` in config but **ignores it**
3. Can't limit tool calls - runs until LLM decides to stop
4. Not representative of how users should build agents with our harness

---

## Comparison with Python mini-swe-agent

| Feature | Python mini-swe-agent | Java MiniAgent (Current) |
|---------|----------------------|--------------------------|
| Turn limiting | Yes (`while turn < max_turns`) | No (config ignored) |
| Explicit loop | Yes (manual while loop) | No (Spring AI internal) |
| Uses own framework | N/A (standalone) | No (bypasses harness-patterns) |
| Governance | Yes | No |

---

## Decision: Option B - Subclass ToolCallAdvisor

After analyzing Spring AI's `ToolCallAdvisor` source code, we found:

1. The tool loop is in `adviseCall()` method (lines 115-163)
2. Loop runs `do { ... } while (isToolCall)` with no iteration limit
3. Protected hook methods exist for extension:
   - `doInitializeLoop()` - before loop starts
   - `doBeforeCall()` - before each LLM call
   - `doAfterCall()` - after each LLM call
   - `doFinalizeLoop()` - when loop ends
4. Builder uses self-referential generics - designed for subclassing

**Approach**: Create `TurnLimitedToolCallAdvisor` that:
- Counts iterations in `doAfterCall()`
- Throws `TurnLimitExceededException` when limit reached
- Preserves partial results for caller

---

## Implementation Plan

### Sequencing: Implement First, Rename After

We implement the turn limiting first, validate it works, then update terminology.
This avoids churn if implementation reveals issues.

### Sub-steps

| Step | Task | Module | Depends On |
|------|------|--------|------------|
| 4.6a | Create `TurnLimitedToolCallAdvisor` | harness-patterns | - |
| 4.6b | Create `TurnLimitExceededException` | harness-patterns | 4.6a |
| 4.6c | Add unit tests for turn limiting | harness-patterns | 4.6a, 4.6b |
| 4.6d | Update MiniAgent to use new advisor | harness-examples | 4.6a, 4.6b |
| 4.6e | Add integration test proving `maxTurns` works | harness-examples | 4.6d |
| 4.6f | Revert terminology: `invocations` → `turnsCompleted` | harness-examples | 4.6e |
| 4.6g | Remove `invocations` field, keep `toolCallsExecuted` | harness-examples | 4.6f |
| 4.6h | Update SPRING-AI-TURN-SEMANTICS.md | research | 4.6g |

**Validation Point**: After 4.6e, we KNOW turn limiting works. Only then do we rename.

---

## Detailed Design

### 4.6a: TurnLimitedToolCallAdvisor

```java
package org.springaicommunity.agents.harness.patterns.advisor;

public class TurnLimitedToolCallAdvisor extends ToolCallAdvisor {

    private final int maxTurns;
    private final ThreadLocal<Integer> turnCount = ThreadLocal.withInitial(() -> 0);

    protected TurnLimitedToolCallAdvisor(ToolCallingManager manager, int order, int maxTurns) {
        super(manager, order);
        this.maxTurns = maxTurns;
    }

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        turnCount.set(0);  // Reset for new run
        return super.doInitializeLoop(request, chain);
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        int current = turnCount.get() + 1;
        turnCount.set(current);

        if (current > maxTurns) {
            throw new TurnLimitExceededException(maxTurns, current, response);
        }

        return super.doAfterCall(response, chain);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends ToolCallAdvisor.Builder<Builder> {
        private int maxTurns = 10;

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        @Override
        public TurnLimitedToolCallAdvisor build() {
            return new TurnLimitedToolCallAdvisor(
                getToolCallingManager(), getAdvisorOrder(), maxTurns);
        }
    }
}
```

### 4.6b: TurnLimitExceededException

```java
package org.springaicommunity.agents.harness.patterns.advisor;

public class TurnLimitExceededException extends RuntimeException {

    private final int maxTurns;
    private final int actualTurns;
    private final ChatClientResponse lastResponse;

    public TurnLimitExceededException(int maxTurns, int actualTurns,
                                       ChatClientResponse lastResponse) {
        super("Turn limit exceeded: %d/%d".formatted(actualTurns, maxTurns));
        this.maxTurns = maxTurns;
        this.actualTurns = actualTurns;
        this.lastResponse = lastResponse;
    }

    public int getMaxTurns() { return maxTurns; }
    public int getActualTurns() { return actualTurns; }
    public ChatClientResponse getLastResponse() { return lastResponse; }

    public String getPartialOutput() {
        if (lastResponse == null || lastResponse.chatResponse() == null) {
            return null;
        }
        return lastResponse.chatResponse().getResult().getOutput().getText();
    }
}
```

### 4.6d: MiniAgent Changes

```java
public MiniAgentResult run(String task) {
    log.info("MiniAgent starting: {}", truncate(task, 80));
    countingListener.reset();
    observationHandler.setContext("mini-agent", 1);

    try {
        ChatResponse response = chatClient.prompt()
                .user(config.systemPrompt() + "\n\nTask: " + task)
                .toolCallbacks(tools)
                .call()
                .chatResponse();

        long tokens = extractTokens(response);
        String output = extractText(response);
        int toolCalls = countingListener.getToolCallCount();

        return new MiniAgentResult("COMPLETED", output, toolCalls, toolCalls,
                                   tokens, tokens * 0.000006);

    } catch (TurnLimitExceededException e) {
        log.warn("Turn limit reached: {}/{}", e.getActualTurns(), e.getMaxTurns());
        return new MiniAgentResult("TURN_LIMIT_REACHED", e.getPartialOutput(),
                                   e.getActualTurns(), countingListener.getToolCallCount(),
                                   0, 0.0);
    }
}
```

---

## Terminology Clarification

After implementation, terminology will be:

| Field | Meaning |
|-------|---------|
| `turnsCompleted` | Iterations of ToolCallAdvisor loop (what we limit) |
| `toolCallsExecuted` | Actual tool executions (multiple possible per turn) |

The `invocations` field (ChatClient.call count) will be removed - it's always 1 with this approach and adds no value.

---

## References

- Spring AI ToolCallAdvisor: `~/projects/spring-ai/.../advisor/ToolCallAdvisor.java`
- Turn semantics: `~/research/appendix/SPRING-AI-TURN-SEMANTICS.md`
- Agent loop taxonomy: `~/research/AGENT-LOOP-TAXONOMY.md`
