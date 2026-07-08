# agent-workflow (Python SDK)

Python authoring SDK for the agent-workflow **Workflow IR** (`workflow/v2alpha`):
author, validate, and emit `WorkflowSpec` JSON that the JVM engine executes.
The SDK is an emitter, never an engine — nothing here executes workflows.

Status: alpha, under active development. The wire contract it targets is the frozen
Conformance Kit at the repository root (`spec/`).

## Development

Managed with [uv](https://docs.astral.sh/uv/); `src/` layout.

```bash
cd sdks/python
uv sync                    # create venv + install (incl. dev group)
uv run pytest              # tests (corpus-driven tests read ../../spec)
uv run ruff check .        # lint
uv run ruff format --check .
uv run mypy                # strict type check (configured in pyproject)
```

The conformance corpus lives at the repo root (`spec/fixtures`, `spec/rules`); the
test suite reads it directly. The JSON Schema is vendored into the distribution at
packaging time.
