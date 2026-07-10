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
    <version>0.2.0</version>
</dependency>
```

## Modules

| Module | Description |
|--------|-------------|
| `workflow-api` | Core interfaces: `Step`, `AgentContext`, `ContextKey` |
| `workflow-core` | Workflow DSL, graph IR, executor, edge conditions |
| `workflow-tools` | Agent tools (Bash, Read, Write, Edit, Glob, Grep) |
| `workflow-flows` | Built-in flow patterns (sequential, parallel, loop) + the v2 `.toSpec()` emitter |
| `workflow-agents` | Ready-to-use agents (AgentLoop, ClaudeStep) |
| `workflow-examples` | Example workflows and usage patterns |
| `workflow-spec` | **V2**: the `WorkflowSpec` model, canonical reader/writer, semantic validator |
| `workflow-engine` | **V2**: the interpreter, operation registry/handler, event stream, `CheckpointStore` SPI |
| `workflow-engine-jpa` | **V2**: durable `CheckpointStore` (crash recovery, resume) |
| `sdks/python`, `sdks/typescript` | **V2**: language-neutral authoring SDKs (emit `WorkflowSpec` JSON) |

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

## V2: Language-Neutral Workflow IR

V2 (in development on the `v2` branch) turns the graph into a **language-neutral,
data-only JSON IR** — `WorkflowSpec`, `apiVersion: workflow/v2alpha` — with a JVM
interpreter and a shared Conformance Kit (`spec/`) that Java, Python, and TypeScript
all validate against. One IR, many authoring surfaces, one engine.

**Three execution planes** (cleanly separable, independently swappable):

- **Control** — `WorkflowInterpreter` reads the inert spec and decides everything:
  bindings, dispatch, retries/timeouts, edge selection, events.
- **Operation** — an `OperationHandler` does *one attempt* of work. Two implementations
  ship: `StepOperationHandler` (runs a `Step` in-process) and any out-of-process
  handler you write (subprocess, HTTP, …).
- **Durability** — a `CheckpointStore` persists progress; the JPA store gives crash
  recovery and resume with no change to the workflow.

**Author once, emit the IR.** Your existing DSL workflow becomes a portable spec:

```java
WorkflowSpecEmission emission = Workflow.<String, Object>define("pr-review")
        .step(fetchDiff).then(analyzeDiff)
        .branch(a -> approved(a)).then(approve).otherwise(reject)
        .build()
        .toSpec();                          // → language-neutral WorkflowSpec JSON

var registry = emission.registerInto(new SimpleOperationRegistry());
var sink = new InMemoryEventSink();
var outcome = new WorkflowInterpreter(registry, sink).run(emission.spec(), "run-1", input);

sink.events().forEach(System.out::println);  // the canonical, inspectable event trace
```

The lambdas are swallowed and auto-registered under deterministic refs — a developer
who never cares about portability never notices the IR exists. See
`workflow-examples/.../v2/` for runnable PR-review and issue-triage workflows,
including an operation served by a separate process.

**Existing `Step` users**: nothing to rewrite. Register your steps and run:

```java
var registry = new SimpleOperationRegistry()
        .register("java:my.fetch:v1", new StepOperationHandler(fetchStep))
        .register("java:my.analyze:v1", new StepOperationHandler(analyzeStep));
```

Or let `.toSpec()` register them for you (the snippet above). The polyglot SDKs author
the same `WorkflowSpec` JSON from Python/TypeScript; the JVM engine executes it.

## Build

Requires Java 21.

```bash
./mvnw clean compile
./mvnw test
```

## License

Business Source License 1.1 — see [LICENSE](LICENSE) for details.
