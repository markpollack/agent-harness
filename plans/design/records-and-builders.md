# Records and Builders Design Decision

## Status
**Proposed** | December 2025

## Summary

This document defines when to use builders versus direct record construction in the spring-ai-agent-harnesses project.

**Core Principle**: If users create it, provide a builder. If the framework creates it, use static factory methods or direct construction.

---

## Decision Matrix

| Category | Fields | Builder? | Construction Pattern |
|----------|--------|----------|---------------------|
| User-facing config | 8+ | **Yes** | `Config.builder().field(x).build()` |
| User-facing config | 3-7 | Maybe | Static factory or builder |
| Framework-created results | Any | No | Static factory methods |
| Framework-created state | Any | No | Direct construction or factory |
| Internal/private records | Any | No | Direct construction |

---

## Record Categories

### Category 1: User-Facing Configuration (Builder Required)

These records are created by users to configure loops. They have many fields with sensible defaults.

| Record | Fields | Builder | Rationale |
|--------|--------|---------|-----------|
| TurnLimitedConfig | 10 | Yes | Many optional fields with defaults |
| EvaluatorOptimizerConfig | 11 | Yes | Many optional fields with defaults |
| StateMachineConfig | 10 | Yes | Complex nested configuration |

**Pattern:**
```java
public record TurnLimitedConfig(
    int maxTurns,
    Duration timeout,
    double scoreThreshold,
    int stuckThreshold,
    double costLimit,
    Path workingDirectory,
    Optional<Jury> jury,
    int evaluateEveryNTurns,
    List<String> tools,
    String finishToolName
) {
    // Compact constructor with validation and defaults
    public TurnLimitedConfig {
        if (maxTurns <= 0) maxTurns = 50;
        if (timeout == null) timeout = Duration.ofMinutes(30);
        if (scoreThreshold <= 0) scoreThreshold = 0.8;
        // ... more defaults
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxTurns = 50;
        private Duration timeout = Duration.ofMinutes(30);
        // ... defaults in builder fields

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public TurnLimitedConfig build() {
            return new TurnLimitedConfig(
                maxTurns, timeout, scoreThreshold, ...);
        }
    }
}
```

**Usage:**
```java
var config = TurnLimitedConfig.builder()
    .maxTurns(100)
    .timeout(Duration.ofHours(1))
    .scoreThreshold(0.9)
    .build();
```

---

### Category 2: User-Facing Small Records (Static Factory Methods)

These records are created by users but have few fields. Static factory methods are cleaner than builders.

| Record | Fields | Builder | Construction Pattern |
|--------|--------|---------|---------------------|
| AgentState | 4 | No | Static factories + constants |
| TransitionResult | 4 | No | Static factories |
| StateContext | 5 | No | Framework + `withAttribute()` |

**Pattern: Static Factory Methods**
```java
public record AgentState(
    String name,
    boolean terminal,
    Set<String> validTransitions,
    String description
) {
    // Predefined constants for common states
    public static final AgentState INITIAL = of("INITIAL", false,
        Set.of("RUNNING", "FAILED"), "Initial state");
    public static final AgentState RUNNING = of("RUNNING", false,
        Set.of("AWAITING_JUDGMENT", "COMPLETED", "FAILED"), "Running state");
    public static final AgentState COMPLETED = of("COMPLETED", true,
        Set.of(), "Terminal success state");
    public static final AgentState FAILED = of("FAILED", true,
        Set.of(), "Terminal failure state");

    // Factory method for custom states
    public static AgentState of(String name, boolean terminal,
                                Set<String> validTransitions, String description) {
        return new AgentState(name, terminal, validTransitions, description);
    }

    // Convenience factory for non-terminal state
    public static AgentState nonTerminal(String name, Set<String> transitions) {
        return new AgentState(name, false, transitions, "");
    }
}
```

**Pattern: Result Factory Methods**
```java
public record TransitionResult(
    String nextState,
    Object output,
    boolean shouldContinue,
    String reason
) {
    // Users call these, not the constructor
    public static TransitionResult stay(Object output) {
        return new TransitionResult(null, output, true, null);
    }

    public static TransitionResult transitionTo(String state, Object output) {
        return new TransitionResult(state, output, true, null);
    }

    public static TransitionResult complete(Object output, String reason) {
        return new TransitionResult("COMPLETED", output, false, reason);
    }

    public static TransitionResult fail(String reason) {
        return new TransitionResult("FAILED", null, false, reason);
    }
}
```

**Usage:**
```java
// Using constants
var state = AgentState.RUNNING;

// Using factory methods
var customState = AgentState.of("REVIEWING", false,
    Set.of("APPROVED", "REJECTED"), "Code review state");

// Transition results
return TransitionResult.transitionTo("RUNNING", output);
return TransitionResult.complete(result, "Task finished");
```

---

### Category 3: Framework-Created Results (Static Factory Methods)

These records are created by the framework, not users. They implement `LoopResult` and use static factory methods for clarity.

| Record | Fields | Builder | Construction Pattern |
|--------|--------|---------|---------------------|
| TurnLimitedResult | 10 | No | `success()`, `terminated()`, `failed()` |
| EvaluatorOptimizerResult | 11 | No | `success()`, `terminated()`, `failed()` |
| StateMachineResult | 11 | No | `success()`, `terminated()`, `failed()` |

**Pattern:**
```java
public record TurnLimitedResult(
    String runId,
    String output,
    LoopStatus status,
    TerminationReason reason,
    int turnsCompleted,
    Duration totalDuration,
    long totalTokens,
    double estimatedCost,
    LoopState finalState,
    @Nullable Verdict lastVerdict
) implements LoopResult {

    // Factory methods for framework use
    public static TurnLimitedResult success(
            LoopState state, String output, Verdict verdict) {
        return new TurnLimitedResult(
            state.runId(),
            output,
            LoopStatus.COMPLETED,
            TerminationReason.SCORE_THRESHOLD_MET,
            state.currentTurn(),
            state.elapsed(),
            state.totalTokensUsed(),
            state.estimatedCost(),
            state,
            verdict
        );
    }

    public static TurnLimitedResult terminated(
            LoopState state, String output, TerminationReason reason,
            @Nullable Verdict verdict) {
        return new TurnLimitedResult(
            state.runId(),
            output,
            LoopStatus.TERMINATED,
            reason,
            state.currentTurn(),
            state.elapsed(),
            state.totalTokensUsed(),
            state.estimatedCost(),
            state,
            verdict
        );
    }

    public static TurnLimitedResult failed(
            LoopState state, TerminationReason reason, Throwable error) {
        return new TurnLimitedResult(
            state.runId(),
            error.getMessage(),
            LoopStatus.FAILED,
            reason,
            state.currentTurn(),
            state.elapsed(),
            state.totalTokensUsed(),
            state.estimatedCost(),
            state,
            null
        );
    }

    // Convenience query methods
    public double finalScore() {
        return lastVerdict != null ? lastVerdict.score() : 0.0;
    }

    public boolean wasStuck() {
        return reason == TerminationReason.STUCK_DETECTED;
    }
}
```

**Framework Usage:**
```java
// In loop implementation
if (verdict.score() >= config.scoreThreshold()) {
    return TurnLimitedResult.success(state, output, verdict);
}

if (state.maxTurnsReached(config.maxTurns())) {
    return TurnLimitedResult.terminated(state, output,
        TerminationReason.MAX_TURNS_REACHED, verdict);
}
```

**Why No Builder:**
- Users never construct these directly
- Factory methods express intent clearly (`success` vs `terminated` vs `failed`)
- All fields come from framework state (LoopState)

---

### Category 4: Framework-Created State Records (Direct Construction)

These records track internal state. Framework constructs them directly.

| Record | Fields | Builder | Construction Pattern |
|--------|--------|---------|---------------------|
| LoopState | 8 | No | `initial()` + mutation methods |
| TurnSnapshot | 5 | No | Created by `LoopState.completeTurn()` |
| TrialRecord | 6 | No | Direct construction in loop |
| StateTransition | 6 | No | Direct construction in loop |

**Pattern: Initial + Mutation**
```java
public record LoopState(
    String runId,
    int currentTurn,
    Instant startedAt,
    long totalTokensUsed,
    double estimatedCost,
    boolean abortSignalled,
    List<TurnSnapshot> turnHistory,
    int consecutiveSameOutputCount
) {
    // Initial state factory
    public static LoopState initial(String runId) {
        return new LoopState(
            runId, 0, Instant.now(), 0L, 0.0, false, List.of(), 0);
    }

    // Immutable update methods (return new instance)
    public LoopState completeTurn(long tokens, double cost,
                                   boolean hadToolCalls, int outputSignature) {
        var snapshot = new TurnSnapshot(
            currentTurn + 1, tokens, cost, hadToolCalls, outputSignature);

        int newStuckCount = calculateStuckCount(outputSignature);

        return new LoopState(
            runId,
            currentTurn + 1,
            startedAt,
            totalTokensUsed + tokens,
            estimatedCost + cost,
            abortSignalled,
            appendToHistory(snapshot),
            newStuckCount
        );
    }

    public LoopState abort() {
        return new LoopState(
            runId, currentTurn, startedAt, totalTokensUsed,
            estimatedCost, true, turnHistory, consecutiveSameOutputCount);
    }
}
```

**Pattern: Simple Nested Records**
```java
// Created only by LoopState.completeTurn() - no factory needed
public record TurnSnapshot(
    int turn,
    long tokensUsed,
    double cost,
    boolean hadToolCalls,
    int outputSignature
) {}

// Created only by loop during trial execution
public record TrialRecord(
    int trialNumber,
    String output,
    double score,
    boolean passed,
    String reflection,
    Duration duration
) {}
```

---

### Category 5: Private/Internal Records (Direct Construction)

These records are implementation details within loop classes. They're private and only used internally.

| Record | Location | Fields | Construction |
|--------|----------|--------|--------------|
| TurnResult | TurnLimitedLoop | 5 | Static factories |
| LoopExecutionResult | TurnLimitedLoop | 4 | Direct |
| PostTurnCheck | TurnLimitedLoop | 4 | Static factories |
| TrialLoopResult | EvaluatorOptimizerLoop | 6 | Direct |
| StateLoopResult | StateMachineLoop | 5 | Direct |

**Pattern: Private Records with Static Factories**
```java
public class TurnLimitedLoop implements AgentLoop<TurnLimitedResult> {

    // Private - only used within this class
    private record TurnResult(
        LoopState state,
        boolean terminated,
        TerminationReason reason,
        Verdict verdict,
        ChatResponse response
    ) {
        static TurnResult continuing(LoopState state, ChatResponse response) {
            return new TurnResult(state, false, null, null, response);
        }

        static TurnResult terminated(LoopState state, TerminationReason reason,
                                     Verdict verdict, ChatResponse response) {
            return new TurnResult(state, true, reason, verdict, response);
        }
    }

    // Private - intermediate aggregation
    private record LoopExecutionResult(
        LoopState state,
        TerminationReason reason,
        Verdict lastVerdict,
        ChatResponse lastResponse
    ) {}
}
```

---

## Summary Table

| Record | Category | Fields | Builder | Factory Methods | Direct |
|--------|----------|--------|---------|-----------------|--------|
| TurnLimitedConfig | User config | 10 | ✅ | | |
| EvaluatorOptimizerConfig | User config | 11 | ✅ | | |
| StateMachineConfig | User config | 10 | ✅ | | |
| AgentState | User small | 4 | | ✅ Constants + `of()` | |
| TransitionResult | User return | 4 | | ✅ `stay()`, `transitionTo()` | |
| StateContext | Mixed | 5 | | | ✅ + `withAttribute()` |
| TurnLimitedResult | Framework result | 10 | | ✅ `success()`, `terminated()` | |
| EvaluatorOptimizerResult | Framework result | 11 | | ✅ `success()`, `terminated()` | |
| StateMachineResult | Framework result | 11 | | ✅ `success()`, `terminated()` | |
| LoopState | Framework state | 8 | | ✅ `initial()` + mutations | |
| TurnSnapshot | Framework nested | 5 | | | ✅ |
| TrialRecord | Framework nested | 6 | | | ✅ |
| StateTransition | Framework nested | 6 | | | ✅ |
| *Private records* | Internal | 4-6 | | Optional | ✅ |

---

## Guidelines

### When to Add a Builder

Add a builder when **all** of these are true:
1. Users create instances directly
2. Record has 8+ fields, OR
3. Most fields have sensible defaults, OR
4. Fields have complex types requiring multiple setup steps

### When to Use Static Factory Methods

Use factory methods when:
1. There are distinct "modes" of construction (`success` vs `failed`)
2. The record has 3-7 fields with clear semantics
3. You want to hide the constructor to enforce invariants
4. You want expressive names (`of()`, `empty()`, `from()`)

### When to Use Direct Construction

Use direct construction when:
1. The record is private/internal
2. The record is a simple data carrier (e.g., `TurnSnapshot`)
3. All fields are required with no defaults
4. Only the framework creates instances

---

## Anti-Patterns to Avoid

### Don't: Builder for Framework-Only Records
```java
// Bad - users never create results
var result = TurnLimitedResult.builder()
    .runId(id)
    .output(output)
    .status(status)
    // ... 10 more fields
    .build();
```

### Don't: Direct Construction for Complex User Config
```java
// Bad - too many positional arguments
var config = new TurnLimitedConfig(
    50, Duration.ofMinutes(30), 0.8, 3, 100.0,
    Path.of("."), Optional.empty(), 1, List.of(), "finish");
```

### Don't: Builder for 3-Field Records
```java
// Overkill - just use factory or direct construction
var snapshot = TurnSnapshot.builder()
    .turn(1)
    .tokensUsed(100)
    .cost(0.01)
    .build();

// Better
var snapshot = new TurnSnapshot(1, 100, 0.01, true, hash);
```

---

## Reference Implementation: Spring-Style Builder for Config Records

Based on patterns from Spring's most popular user-facing builders:
- **RestTemplateBuilder** (Spring Boot) - immutable builder, every method returns new instance
- **RestClient.Builder** (Spring Framework 6.1+) - modern pattern with `clone()` and `apply(Consumer)`
- **WebClient.Builder** (Spring WebFlux) - reactive equivalent with same conventions

These are the builders developers use daily for configuring HTTP clients, making them excellent references for configuration classes.

### Complete Example: TurnLimitedConfig

```java
/**
 * Configuration for turn-limited multi-condition loops.
 *
 * <p>Use the {@link #builder()} method to create instances:
 * <pre>{@code
 * TurnLimitedConfig config = TurnLimitedConfig.builder()
 *     .maxTurns(100)
 *     .timeout(Duration.ofHours(1))
 *     .scoreThreshold(0.9)
 *     .build();
 * }</pre>
 *
 * @see TurnLimitedLoop
 */
public record TurnLimitedConfig(
    int maxTurns,
    Duration timeout,
    double scoreThreshold,
    int stuckThreshold,
    double costLimit,
    Path workingDirectory,
    Optional<Jury> jury,
    int evaluateEveryNTurns,
    List<String> tools,
    String finishToolName
) {
    /**
     * Compact constructor with validation.
     * Defaults are applied in the Builder, not here.
     */
    public TurnLimitedConfig {
        Assert.isTrue(maxTurns > 0, "maxTurns must be positive");
        Assert.notNull(timeout, "timeout must not be null");
        Assert.isTrue(scoreThreshold >= 0 && scoreThreshold <= 1,
            "scoreThreshold must be between 0 and 1");
        Assert.isTrue(stuckThreshold >= 0, "stuckThreshold must not be negative");
        Assert.isTrue(costLimit >= 0, "costLimit must not be negative");
        Assert.notNull(tools, "tools must not be null");

        // Defensive copies for immutability
        tools = List.copyOf(tools);
    }

    /**
     * Returns a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with values from this config.
     * Useful for creating modified copies.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Builder for {@link TurnLimitedConfig}.
     *
     * <p>All fields have sensible defaults. Only override what you need.
     */
    public static final class Builder {

        // Defaults defined here, not in record
        private int maxTurns = 50;
        private Duration timeout = Duration.ofMinutes(30);
        private double scoreThreshold = 0.8;
        private int stuckThreshold = 3;
        private double costLimit = 100.0;
        private Path workingDirectory = Path.of(".");
        private Optional<Jury> jury = Optional.empty();
        private int evaluateEveryNTurns = 1;
        private final List<String> tools = new ArrayList<>();
        private String finishToolName = "finish";

        /**
         * Creates a new builder with default values.
         */
        Builder() {
        }

        /**
         * Creates a builder initialized from an existing config.
         * (Copy constructor pattern from Spring Security)
         */
        Builder(TurnLimitedConfig config) {
            this.maxTurns = config.maxTurns();
            this.timeout = config.timeout();
            this.scoreThreshold = config.scoreThreshold();
            this.stuckThreshold = config.stuckThreshold();
            this.costLimit = config.costLimit();
            this.workingDirectory = config.workingDirectory();
            this.jury = config.jury();
            this.evaluateEveryNTurns = config.evaluateEveryNTurns();
            this.tools.addAll(config.tools());
            this.finishToolName = config.finishToolName();
        }

        /**
         * Sets the maximum number of turns before termination.
         * @param maxTurns the maximum turns (must be positive)
         * @return this builder
         */
        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /**
         * Sets the timeout duration.
         * @param timeout the timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the score threshold for successful completion.
         * @param scoreThreshold the threshold (0.0 to 1.0)
         * @return this builder
         */
        public Builder scoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * Sets the stuck detection threshold.
         * @param stuckThreshold consecutive identical outputs to trigger stuck detection
         * @return this builder
         */
        public Builder stuckThreshold(int stuckThreshold) {
            this.stuckThreshold = stuckThreshold;
            return this;
        }

        /**
         * Sets the cost limit in dollars.
         * @param costLimit the maximum cost
         * @return this builder
         */
        public Builder costLimit(double costLimit) {
            this.costLimit = costLimit;
            return this;
        }

        /**
         * Sets the working directory for file operations.
         * @param workingDirectory the working directory path
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Sets the jury for evaluation.
         * @param jury the jury instance
         * @return this builder
         */
        public Builder jury(Jury jury) {
            this.jury = Optional.ofNullable(jury);
            return this;
        }

        /**
         * Sets how often to evaluate (every N turns).
         * @param evaluateEveryNTurns evaluation frequency
         * @return this builder
         */
        public Builder evaluateEveryNTurns(int evaluateEveryNTurns) {
            this.evaluateEveryNTurns = evaluateEveryNTurns;
            return this;
        }

        /**
         * Adds a tool to the available tools list.
         * (Single-item add pattern from Spring Security)
         * @param tool the tool name
         * @return this builder
         */
        public Builder tool(String tool) {
            this.tools.add(tool);
            return this;
        }

        /**
         * Sets the tools list, replacing any existing tools.
         * @param tools the tool names
         * @return this builder
         */
        public Builder tools(List<String> tools) {
            this.tools.clear();
            this.tools.addAll(tools);
            return this;
        }

        /**
         * Provides access to modify the tools list directly.
         * (Consumer pattern from Spring Security)
         * @param toolsConsumer a consumer to modify the tools list
         * @return this builder
         */
        public Builder tools(Consumer<List<String>> toolsConsumer) {
            toolsConsumer.accept(this.tools);
            return this;
        }

        /**
         * Sets the finish tool name.
         * @param finishToolName the name of the finish tool
         * @return this builder
         */
        public Builder finishToolName(String finishToolName) {
            this.finishToolName = finishToolName;
            return this;
        }

        /**
         * Applies custom configuration via a consumer.
         * (Pattern from RestClient.Builder, WebClient.Builder)
         * @param configurer a consumer to apply configuration
         * @return this builder
         */
        public Builder apply(Consumer<Builder> configurer) {
            configurer.accept(this);
            return this;
        }

        /**
         * Creates a copy of this builder for independent modification.
         * (Pattern from RestClient.Builder)
         * @return a new builder with copied state
         */
        public Builder copy() {
            return new Builder(this);
        }

        /**
         * Builds the configuration.
         * Validation is performed by the record's compact constructor.
         * @return the built configuration
         * @throws IllegalArgumentException if validation fails
         */
        public TurnLimitedConfig build() {
            return new TurnLimitedConfig(
                maxTurns,
                timeout,
                scoreThreshold,
                stuckThreshold,
                costLimit,
                workingDirectory,
                jury,
                evaluateEveryNTurns,
                tools,
                finishToolName
            );
        }
    }
}
```

### Key Spring Conventions Applied

1. **Static `builder()` method** - Entry point for construction
2. **`toBuilder()` method** - Create modified copies (copy-on-write)
3. **Defaults in Builder fields** - Not in record constructor
4. **Validation in record compact constructor** - Using Spring's `Assert`
5. **Defensive copies** - `List.copyOf()` for immutability
6. **Single-item add methods** - `tool(String)` for collections
7. **Consumer-based modification** - `tools(Consumer<List<String>>)` for complex changes
8. **Package-private Builder constructor** - `Builder()` not public
9. **Javadoc on all public methods** - Following Spring documentation standards

### Usage Examples

```java
// Basic usage with defaults
var config = TurnLimitedConfig.builder()
    .maxTurns(100)
    .build();

// Full configuration
var config = TurnLimitedConfig.builder()
    .maxTurns(100)
    .timeout(Duration.ofHours(2))
    .scoreThreshold(0.95)
    .stuckThreshold(5)
    .costLimit(50.0)
    .workingDirectory(Path.of("/tmp/agent"))
    .jury(myJury)
    .evaluateEveryNTurns(3)
    .tool("read_file")
    .tool("write_file")
    .tool("execute_command")
    .finishToolName("task_complete")
    .build();

// Copy and modify (toBuilder pattern)
var stricterConfig = existingConfig.toBuilder()
    .scoreThreshold(0.99)
    .maxTurns(200)
    .build();

// Consumer-based tool modification
var config = TurnLimitedConfig.builder()
    .tools(tools -> {
        tools.add("read_file");
        tools.add("write_file");
        tools.removeIf(t -> t.startsWith("dangerous_"));
    })
    .build();
```

### Pattern Summary

| Convention | Example | Source |
|------------|---------|--------|
| Static factory | `builder()` | RestClient, WebClient, RestTemplateBuilder |
| Copy builder | `toBuilder()` / `copy()` | RestClient.Builder, WebClient.Builder |
| Single-item add | `tool(String)` | RestTemplateBuilder `interceptor()` |
| Collection replace | `tools(List)` | RestTemplateBuilder `interceptors()` |
| Consumer modification | `tools(Consumer)` | RestClient `defaultHeaders(Consumer)` |
| Apply hook | `apply(Consumer<Builder>)` | RestClient.Builder, WebClient.Builder |
| Validation in record | `Assert.isTrue(...)` | Spring Framework convention |
| Package-private constructor | `Builder()` | Spring Boot convention |

### Additional Patterns from RestClient/WebClient

```java
/**
 * Applies custom configuration via a consumer.
 * Useful for reusable configuration snippets.
 */
public Builder apply(Consumer<Builder> configurer) {
    configurer.accept(this);
    return this;
}

/**
 * Creates a copy of this builder for independent modification.
 */
public Builder clone() {
    return new Builder(this);
}
```

**Usage with apply():**
```java
// Reusable configuration snippet
Consumer<TurnLimitedConfig.Builder> strictConfig = builder -> builder
    .scoreThreshold(0.95)
    .stuckThreshold(5)
    .maxTurns(200);

// Apply to different configs
var config1 = TurnLimitedConfig.builder()
    .apply(strictConfig)
    .timeout(Duration.ofHours(1))
    .build();

var config2 = TurnLimitedConfig.builder()
    .apply(strictConfig)
    .timeout(Duration.ofMinutes(30))
    .build();
```

---

## Why Not Lombok?

With only 3 config classes requiring builders (`TurnLimitedConfig`, `EvaluatorOptimizerConfig`, `StateMachineConfig`), Lombok is not justified.

**Costs of Lombok:**
- Dependency management across modules
- IDE plugin required for all developers
- Annotation processing configuration in build
- Debugging opacity (generated code not visible)
- Potential issues with record + @Builder edge cases

**Cost of Manual Builders:**
- ~50-60 lines per config class (~150-180 lines total)
- Straightforward, debuggable code
- Full control over validation and defaults
- No external dependencies
- Consistent with Spring Framework conventions

**Recommendation:** Use manual builders for the 3 config classes. The small amount of additional code is worth the simplicity and control.
