# AgentLoop Result Types Design Decision

## Status
**Proposed** | December 2025

## Summary

Replace the current `AgentLoop<S>` generic with `AgentLoop<R extends LoopResult>`, eliminating SummaryBuilder boilerplate while preserving type-safe results and allowing user-defined loop implementations.

---

## Context

The current `AgentLoop<S>` design requires users to provide a `SummaryBuilder<S>` functional interface to transform loop execution data into a summary type. Each loop type has its own SummaryBuilder signature:

| Loop | Current SummaryBuilder Signature |
|------|----------------------------------|
| TurnLimitedLoop | `S build(LoopState, TerminationReason, Verdict, ChatResponse)` |
| EvaluatorOptimizerLoop | `S build(String output, List<TrialRecord>, LoopState, TerminationReason)` |
| StateMachineLoop | `S build(String output, List<StateTransition>, AgentState, TerminationReason)` |

### Problems with Current Design

1. **Boilerplate**: Users must always provide a SummaryBuilder:
   ```java
   var loop = TurnLimitedLoop.<String>builder()
       .config(config)
       .summaryBuilder((state, reason, verdict, response) ->
           getTextContent(response))  // Most users just want the output string
       .build();
   ```

2. **Lost Data**: When users return a simple type like `String`, they lose access to pattern-specific data (trials, transitions, verdicts).

3. **Complexity**: The `<S>` generic propagates throughout the API.

4. **Inconsistent Signatures**: Each loop has a different SummaryBuilder signature.

---

## Decision

### Core Interfaces

```java
/**
 * Common contract for all loop results.
 * Implementations provide pattern-specific data as additional fields.
 *
 * <p>This is a regular interface (not sealed) to allow user-defined
 * loop implementations with custom result types.
 */
public interface LoopResult {

    String runId();
    String output();
    LoopStatus status();
    TerminationReason reason();
    int turnsCompleted();
    Duration totalDuration();
    long totalTokens();
    double estimatedCost();

    default boolean isSuccess() {
        return status() == LoopStatus.COMPLETED;
    }

    default boolean isFailure() {
        return status() == LoopStatus.FAILED || status() == LoopStatus.ERROR;
    }
}
```

```java
/**
 * Central abstraction for agentic loop patterns.
 *
 * <p>The type parameter R allows each loop implementation to return
 * its specific result type, providing type-safe access to pattern-specific
 * data without casting when the loop type is known at compile time.
 *
 * @param <R> The specific result type this loop returns
 */
public interface AgentLoop<R extends LoopResult> {

    /**
     * Executes the agent loop.
     *
     * @param userMessage the user message to process
     * @param chatClient the Spring AI ChatClient to use
     * @param tools available tool callbacks
     * @return the loop result with pattern-specific data
     */
    R execute(String userMessage, ChatClient chatClient, List<ToolCallback> tools);

    TerminationStrategy terminationStrategy();

    LoopType loopType();
}
```

---

## Result Types

### TurnLimitedResult

```java
public record TurnLimitedResult(
    // Common fields
    String runId,
    String output,
    LoopStatus status,
    TerminationReason reason,
    int turnsCompleted,
    Duration totalDuration,
    long totalTokens,
    double estimatedCost,

    // Pattern-specific fields
    LoopState finalState,
    @Nullable Verdict lastVerdict,
    List<TurnSnapshot> turnHistory
) implements LoopResult {

    public double finalScore() {
        return lastVerdict != null ? lastVerdict.score() : 0.0;
    }

    public boolean wasStuck() {
        return reason() == TerminationReason.STUCK_DETECTED;
    }
}
```

### EvaluatorOptimizerResult

```java
public record EvaluatorOptimizerResult(
    // Common fields
    String runId,
    String output,
    LoopStatus status,
    TerminationReason reason,
    int turnsCompleted,
    Duration totalDuration,
    long totalTokens,
    double estimatedCost,

    // Pattern-specific fields
    List<TrialRecord> trials,
    double bestScore,
    int totalTrials,
    @Nullable String bestReflection
) implements LoopResult {

    public double scoreImprovement() {
        if (trials.isEmpty()) return 0.0;
        return bestScore - trials.get(0).score();
    }

    public boolean thresholdMet() {
        return reason() == TerminationReason.SCORE_THRESHOLD_MET;
    }

    public Optional<TrialRecord> bestTrial() {
        return trials.stream()
            .filter(t -> t.score() == bestScore)
            .findFirst();
    }
}
```

### StateMachineResult

```java
public record StateMachineResult(
    // Common fields
    String runId,
    String output,
    LoopStatus status,
    TerminationReason reason,
    int turnsCompleted,
    Duration totalDuration,
    long totalTokens,
    double estimatedCost,

    // Pattern-specific fields
    List<StateTransition> transitions,
    AgentState finalState,
    Map<String, Object> finalAttributes
) implements LoopResult {

    public boolean reachedTerminalState() {
        return finalState.terminal() && reason() == TerminationReason.STATE_TERMINAL;
    }

    public List<String> stateSequence() {
        if (transitions.isEmpty()) return List.of();
        List<String> sequence = new ArrayList<>();
        sequence.add(transitions.get(0).fromState());
        transitions.forEach(t -> sequence.add(t.toState()));
        return sequence;
    }

    public boolean visitedState(String stateName) {
        return transitions.stream()
            .anyMatch(t -> t.fromState().equals(stateName) || t.toState().equals(stateName));
    }
}
```

---

## Loop Implementations

### TurnLimitedLoop

```java
public class TurnLimitedLoop implements AgentLoop<TurnLimitedResult> {

    private final TurnLimitedConfig config;
    private final TerminationStrategy terminationStrategy;
    private final List<LoopListener> listeners;

    private TurnLimitedLoop(Builder builder) {
        this.config = builder.config;
        this.terminationStrategy = buildTerminationStrategy(builder.config);
        this.listeners = List.copyOf(builder.listeners);
    }

    @Override
    public TurnLimitedResult execute(String userMessage, ChatClient chatClient, List<ToolCallback> tools) {
        // ... execution logic ...

        return new TurnLimitedResult(
            state.runId(),
            extractOutput(lastResponse),
            determineStatus(reason),
            reason,
            state.currentTurn(),
            state.elapsed(),
            state.totalTokensUsed(),
            state.estimatedCost(),
            state,
            lastVerdict,
            state.turnHistory()
        );
    }

    @Override
    public LoopType loopType() {
        return LoopType.TURN_LIMITED_MULTI_CONDITION;
    }

    @Override
    public TerminationStrategy terminationStrategy() {
        return terminationStrategy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TurnLimitedConfig config;
        private List<LoopListener> listeners = new ArrayList<>();

        public Builder config(TurnLimitedConfig config) {
            this.config = config;
            return this;
        }

        public Builder listener(LoopListener listener) {
            this.listeners.add(listener);
            return this;
        }

        public TurnLimitedLoop build() {
            Objects.requireNonNull(config, "config is required");
            return new TurnLimitedLoop(this);
        }
    }
}
```

### EvaluatorOptimizerLoop

```java
public class EvaluatorOptimizerLoop implements AgentLoop<EvaluatorOptimizerResult> {

    @Override
    public EvaluatorOptimizerResult execute(String userMessage, ChatClient chatClient, List<ToolCallback> tools) {
        // ... execution logic ...

        return new EvaluatorOptimizerResult(
            state.runId(),
            bestOutput,
            determineStatus(reason),
            reason,
            state.currentTurn(),
            state.elapsed(),
            state.totalTokensUsed(),
            state.estimatedCost(),
            trials,
            bestScore,
            trials.size(),
            bestReflection
        );
    }

    @Override
    public LoopType loopType() {
        return LoopType.EVALUATOR_OPTIMIZER;
    }
}
```

### StateMachineLoop

```java
public class StateMachineLoop implements AgentLoop<StateMachineResult> {

    @Override
    public StateMachineResult execute(String userMessage, ChatClient chatClient, List<ToolCallback> tools) {
        // ... execution logic ...

        return new StateMachineResult(
            state.runId(),
            lastOutput,
            determineStatus(reason),
            reason,
            state.currentTurn(),
            state.elapsed(),
            state.totalTokensUsed(),
            state.estimatedCost(),
            transitions,
            finalState,
            context.attributes()
        );
    }

    @Override
    public LoopType loopType() {
        return LoopType.STATUS_BASED_STATE_MACHINE;
    }
}
```

---

## Usage Examples

### Basic Usage (Known Loop Type)

When the loop type is known at compile time, you get type-safe access to all fields without casting:

```java
// TurnLimitedLoop
TurnLimitedLoop loop = TurnLimitedLoop.builder()
    .config(config)
    .build();

TurnLimitedResult result = loop.execute(message, chatClient, tools);

String output = result.output();
double score = result.finalScore();
boolean stuck = result.wasStuck();
List<TurnSnapshot> history = result.turnHistory();
```

```java
// EvaluatorOptimizerLoop
EvaluatorOptimizerLoop loop = EvaluatorOptimizerLoop.builder()
    .config(config)
    .build();

EvaluatorOptimizerResult result = loop.execute(message, chatClient, tools);

List<TrialRecord> trials = result.trials();
double improvement = result.scoreImprovement();
Optional<TrialRecord> best = result.bestTrial();
```

```java
// StateMachineLoop
StateMachineLoop loop = StateMachineLoop.builder()
    .config(config)
    .build();

StateMachineResult result = loop.execute(message, chatClient, tools);

List<StateTransition> transitions = result.transitions();
AgentState finalState = result.finalState();
List<String> stateFlow = result.stateSequence();
```

### Polymorphic Usage (Unknown Loop Type)

When working with loops generically, use `instanceof` to access pattern-specific data:

```java
public void runLoop(AgentLoop<?> loop, String message, ChatClient client, List<ToolCallback> tools) {
    LoopResult result = loop.execute(message, client, tools);

    // Common access - always available
    log.info("Completed: status={}, turns={}, tokens={}, cost=${}",
        result.status(),
        result.turnsCompleted(),
        result.totalTokens(),
        result.estimatedCost());

    if (result.isFailure()) {
        log.error("Failed: {}", result.reason());
        return;
    }

    // Pattern-specific access when needed
    if (result instanceof TurnLimitedResult r) {
        log.info("Final score: {}, stuck: {}", r.finalScore(), r.wasStuck());

    } else if (result instanceof EvaluatorOptimizerResult r) {
        log.info("Trials: {}, improvement: {}", r.totalTrials(), r.scoreImprovement());

    } else if (result instanceof StateMachineResult r) {
        log.info("State flow: {}", r.stateSequence());

    } else {
        // Handle unknown/custom result types
        log.info("Output: {}", result.output());
    }
}
```

### User-Defined Custom Loop

Users can implement their own loops with custom result types:

```java
// Custom result type
public record MyCustomResult(
    String runId,
    String output,
    LoopStatus status,
    TerminationReason reason,
    int turnsCompleted,
    Duration totalDuration,
    long totalTokens,
    double estimatedCost,

    // Custom pattern-specific fields
    List<MyStepRecord> steps,
    MyDomainMetrics metrics
) implements LoopResult {

    public int successfulSteps() {
        return (int) steps.stream().filter(MyStepRecord::succeeded).count();
    }
}

// Custom loop implementation
public class MyCustomLoop implements AgentLoop<MyCustomResult> {

    @Override
    public MyCustomResult execute(String userMessage, ChatClient chatClient, List<ToolCallback> tools) {
        // Custom execution logic
        return new MyCustomResult(
            UUID.randomUUID().toString(),
            output,
            LoopStatus.COMPLETED,
            TerminationReason.FINISH_TOOL_CALLED,
            turns,
            duration,
            tokens,
            cost,
            steps,
            metrics
        );
    }

    @Override
    public TerminationStrategy terminationStrategy() {
        return myStrategy;
    }

    @Override
    public LoopType loopType() {
        return LoopType.CUSTOM;  // Or add to enum
    }
}

// Usage - fully type-safe
MyCustomLoop loop = new MyCustomLoop(config);
MyCustomResult result = loop.execute(message, client, tools);
int successful = result.successfulSteps();
```

---

## Test Examples

```java
@Test
void turnLimitedLoop_shouldCompleteWithinTurnLimit() {
    TurnLimitedLoop loop = TurnLimitedLoop.builder()
        .config(TurnLimitedConfig.builder().maxTurns(10).build())
        .build();

    TurnLimitedResult result = loop.execute("Solve this", chatClient, tools);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.turnsCompleted()).isLessThanOrEqualTo(10);
    assertThat(result.finalScore()).isGreaterThan(0.8);
    assertThat(result.turnHistory()).isNotEmpty();
}

@Test
void evaluatorLoop_shouldImproveOverTrials() {
    EvaluatorOptimizerLoop loop = EvaluatorOptimizerLoop.builder()
        .config(config)
        .build();

    EvaluatorOptimizerResult result = loop.execute("Optimize this", chatClient, tools);

    assertThat(result.scoreImprovement()).isPositive();
    assertThat(result.trials()).hasSizeGreaterThan(1);
    assertThat(result.bestTrial()).isPresent();
}

@Test
void stateMachineLoop_shouldReachTerminalState() {
    StateMachineLoop loop = StateMachineLoop.builder()
        .config(config)
        .build();

    StateMachineResult result = loop.execute("Process this", chatClient, tools);

    assertThat(result.reachedTerminalState()).isTrue();
    assertThat(result.visitedState("RUNNING")).isTrue();
    assertThat(result.stateSequence()).contains("INITIAL", "RUNNING", "COMPLETED");
}
```

---

## Migration Guide

### Before (Current Design)

```java
// Must specify generic type and provide SummaryBuilder
var loop = TurnLimitedLoop.<MyResult>builder()
    .config(config)
    .summaryBuilder((state, reason, verdict, response) -> {
        return new MyResult(
            getTextContent(response),
            verdict != null ? verdict.score() : 0.0,
            state.currentTurn()
        );
    })
    .build();

LoopResult<MyResult> result = loop.execute(message, chatClient, tools);
MyResult summary = result.summary();
```

### After (New Design)

```java
// No generic type parameter, no SummaryBuilder
var loop = TurnLimitedLoop.builder()
    .config(config)
    .build();

TurnLimitedResult result = loop.execute(message, chatClient, tools);

// All data directly available
String output = result.output();
double score = result.finalScore();
int turns = result.turnsCompleted();
```

### If Custom Result Type Still Needed

Transform after execution:

```java
TurnLimitedResult result = loop.execute(message, chatClient, tools);

MyResult custom = new MyResult(
    result.output(),
    result.finalScore(),
    result.turnsCompleted()
);
```

---

## Design Rationale

### Why `<R extends LoopResult>` Instead of `<S>`?

| Aspect | Old `<S>` | New `<R extends LoopResult>` |
|--------|-----------|------------------------------|
| Purpose | User-defined summary type | Bounded result type |
| SummaryBuilder | Required | Eliminated |
| Pattern-specific data | Lost unless in S | Always available |
| Type safety | Yes | Yes |
| Boilerplate | High | Minimal |

### Why Not Sealed Interface?

A sealed interface would provide exhaustive switch checking but prevents users from implementing custom loops. Since extensibility is a requirement, we use a regular interface.

Trade-off:
- **Lost**: Compiler-enforced exhaustive switch (must use `default` case)
- **Gained**: Users can create custom AgentLoop implementations

### Why Interface Instead of Abstract Class?

- Loops may need to extend other classes
- Interface allows multiple inheritance of type
- No shared implementation needed at the LoopResult level

---

## Summary

| Aspect | Before | After |
|--------|--------|-------|
| Generic parameter | `AgentLoop<S>` | `AgentLoop<R extends LoopResult>` |
| SummaryBuilder | Required | Removed |
| Result access | `result.summary()` returns `S` | Direct access to all fields |
| Pattern-specific data | Lost unless in S | Always available |
| User-defined loops | Possible but complex | Simple - just implement interface |
| Boilerplate | High | Minimal |

---

## File Locations

After implementation, the key files will be:

```
harness-api/src/main/java/org/springaicommunity/agents/harness/core/
├── AgentLoop.java              # Interface with <R extends LoopResult>
├── LoopResult.java             # Common result interface
├── LoopStatus.java             # Enum
└── TerminationReason.java      # Enum

harness-patterns/src/main/java/org/springaicommunity/agents/harness/patterns/
├── turnlimited/
│   ├── TurnLimitedLoop.java
│   └── TurnLimitedResult.java
├── evaluator/
│   ├── EvaluatorOptimizerLoop.java
│   └── EvaluatorOptimizerResult.java
└── statemachine/
    ├── StateMachineLoop.java
    └── StateMachineResult.java
```
