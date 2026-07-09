# agent-workflow (Python SDK)

Python authoring SDK for the agent-workflow **Workflow IR** (`workflow/v2alpha`):
author, validate, and emit `WorkflowSpec` JSON that the JVM engine executes.

**What this SDK is not**: an engine. Nothing here executes workflows — the SDK is an
emitter over a language-neutral IR. One IR, one engine, idiomatic authoring per
language: the same workflow authored here, in the Java DSL, or by hand emits
byte-identical canonical JSON.

## Quickstart

```python
from agent_workflow import WorkflowBuilder, from_input, from_node_output

wf = WorkflowBuilder("greeter", version="1.0.0")
wf.operation("greet", ref="java:demo.greet:v1")
wf.task("hello", operation="greet", input={"name": from_input("name")})
wf.terminate("done", status="completed", result=from_node_output("hello"))
wf.edge("hello", "done")

spec = wf.build(entrypoint="hello")   # runs BOTH validation phases — authoring
                                      # errors raise WorkflowValidationError here
spec.to_json()                        # canonical UTF-8 bytes (RFC 8785)
```

Reading and validating existing specs:

```python
from agent_workflow import load, validate

spec = load(open("pr-review.workflow.json", "rb").read())   # validated on read
errors = validate(spec)                                     # semantic phase, on demand
```

## The golden example

`examples/golden_pr_review.py` authors the Conformance Kit's golden pr-review
workflow — a fetch → analyze → decision fan-out with context writes, constants, and
two terminate paths — and emits bytes canonically equal to the shared fixture:

```bash
uv run python examples/golden_pr_review.py > pr-review.workflow.json
```

`examples/resilient_fetch.py` shows retry policies (`max_attempts` counts the first
attempt), deterministic backoff, per-attempt timeouts, and error-edge routing.

## Execution handoff

The emitted JSON is the entire contract. Hand it to the JVM engine any way you like
(file, HTTP, queue):

```java
WorkflowSpec spec = new DefaultWorkflowSpecReader().read(inputStream); // re-validates
WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink)
        .run(spec, workflowRunId, input);
```

The engine re-validates on load regardless of what the SDK did, resolves each
declared operation `ref` through its `OperationRegistry`, executes, and emits the
canonical event stream. Operation refs are capability identifiers — a
`python:`-prefixed ref documents intent and resolves to whatever handler is
registered under that string (Python operation *workers* are a future increment;
authoring is what ships here).

## Development

Managed with [uv](https://docs.astral.sh/uv/); `src/` layout.

```bash
cd sdks/python
uv sync                    # create venv + install (incl. dev group)
uv run pytest              # tests (corpus-driven tests read ../../spec)
uv run ruff check .        # lint
uv run ruff format --check .
uv run mypy                # strict type check (configured in pyproject)
uv build                   # sdist + wheel
```

The conformance corpus lives at the repo root (`spec/fixtures`, `spec/rules`); the
test suite reads it directly. The JSON Schema is vendored into the distribution
(`agent_workflow/_schema/`) and a test pins it byte-equal to the Kit's copy.
