# Agent Workflow

Composable agentic pipeline patterns for Spring AI — steps, typed context, branching, loops, quality gates.

Compose **steps** into **workflows** using a fluent Java DSL. Each step does one thing: call an LLM, run a function, invoke an external agent. Quality gates evaluate output at each stage. Every step transition is traced for behavioral analysis — so you can answer: *which steps should be deterministic instead of LLM-driven? What knowledge is the agent missing? Does it need better real-time steering?*

The workflow compiles to a **graph intermediate representation** that separates definition from execution, enabling portable runtimes without changing workflow code.

**Documentation**: [lab.pollack.ai/projects/agent-workflow](https://lab.pollack.ai/projects/agent-workflow)

## Coordinates

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>workflow-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Modules

| Module | Description |
|--------|-------------|
| `workflow-api` | Core interfaces: `Step`, `AgentContext`, `ContextKey` |
| `workflow-core` | Workflow DSL, graph IR, executor, edge conditions |
| `workflow-tools` | Agent tools (Bash, Read, Write, Edit, Glob, Grep) |
| `workflow-flows` | Built-in flow patterns (sequential, parallel, loop) |
| `workflow-agents` | Ready-to-use agents (MiniAgent, ClaudeStep) |
| `workflow-examples` | Example workflows and usage patterns |

## Quick Example

```java
Workflow.define("pr-review")
    .step(fetchDiff)
    .then(analyzeDiff)
    .gate(new JudgeGate(jury, 0.8))
        .onPass(postComment)
        .onFail(revise)
    .end()
    .run(event);
```

## Steps

Steps are the building blocks. Each takes input, does work, produces output:

- **Deterministic** — a Java function (API call, parsing, formatting)
- **Single LLM call** — `ChatClientStep` wraps a [Spring AI](https://spring.io/projects/spring-ai) `ChatClient`
- **Agentic session** — `ClaudeStep` runs a full multi-turn agent loop (dozens of tool calls, minutes of execution) and returns a typed result. The workflow sees it as one step.

## DSL Primitives

`step` · `then` · `branch` · `repeatUntil` · `repeatUntilOutput` · `parallel` · `decision` · `gate` · `supervisor` · `onError` · `terminate`

## Why a Graph

The workflow definition is pure data — nodes and edges, not opaque lambdas. This enables:

- **Portable runtimes** — `LocalStepRunner` (in-process, zero overhead), `CheckpointingStepRunner` (JDBC crash recovery), `TemporalStepRunner` (distributed durable execution). Same workflow code, swap a `@Bean`.
- **Tracing** — every step transition recorded for observability and behavioral analysis
- **Quality gates** — `JudgeGate` evaluates output mid-pipeline, routes to retry with verdict feedback
- **Inspection** — the compiled graph is serializable, walkable, visualizable

## Build

Requires Java 21.

```bash
./mvnw clean compile
./mvnw test
```

## License

Business Source License 1.1 — see [LICENSE](LICENSE) for details.
