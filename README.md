# Agent Workflow

A Spring-native DSL for composing multi-step agentic pipelines. Define workflows as fluent Java chains that compile to an inspectable graph IR, execute via swappable runtimes, and record every transition for analysis.

**Full documentation**: [lab.pollack.ai/projects/agent-workflow](https://lab.pollack.ai/projects/agent-workflow)

## Quick Example

```java
String result = Workflow.<String, String>define("pr-review")
        .step(fetchDiff)
        .then(analyzeDiff)
        .branch(output -> ((Analysis) output).isHighRisk())
            .then(detailedReview)
            .otherwise(quickReview)
        .gate(new JudgeGate<>(jury, 0.8))
            .onPass(postComment)
            .onFail(reviseWithFeedback)
        .end()
        .run(prEvent);
```

## What It Does

- **Fluent DSL** — `step`, `then`, `branch`, `repeatUntil`, `repeatUntilOutput`, `parallel`, `decision`, `gate`, `supervisor`, `onError`, `terminate`
- **Graph IR** — every workflow compiles to a `WorkflowGraph` with sealed `WorkflowNode` variants and typed `EdgeCondition` edges. Inspectable, serializable, walkable.
- **Quality Gates** — `JudgeGate` with [spring-ai-agents-judge](https://github.com/spring-ai-community/agent-judge) integration, verdict feedback, reflector step
- **Step Granularity** — a `Step` is a full agentic loop (minutes of execution, dozens of LLM calls), not a single API call
- **Swappable Runtime** — `LocalStepRunner` (default), `CheckpointingStepRunner` (JDBC crash recovery), `TemporalStepRunner` (distributed) — same workflow code, different `@Bean`
- **Context** — type-safe `ContextKey<T>` entries, auto-propagation via `Steps.outputOf()`, step metadata via `updateContext()`

## Build

Requires Java 21.

```bash
./mvnw clean compile
./mvnw test
```

## License

Business Source License 1.1 — see [LICENSE](LICENSE) for details.
