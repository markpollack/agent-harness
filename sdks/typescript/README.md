# @markpollack/agent-workflow (TypeScript SDK)

TypeScript authoring SDK for the agent-workflow **Workflow IR** (`workflow/v2alpha`):
author, validate, and emit `WorkflowSpec` JSON that the JVM engine executes. The SDK
is an emitter, never an engine — nothing here executes workflows.

Status: alpha, under active development. The wire contract it targets is the frozen
Conformance Kit at the repository root (`spec/`).

## Development

Managed with [pnpm](https://pnpm.io/); ESM-first.

```bash
cd sdks/typescript
pnpm install
pnpm test              # vitest (corpus-driven tests read ../../spec)
pnpm run typecheck     # tsc --noEmit (strict)
pnpm run lint          # eslint
pnpm run format:check  # prettier
pnpm run build         # tsup → dist (esm + .d.ts)
pnpm run check         # lint + format + typecheck + test
```

The conformance corpus lives at the repo root (`spec/fixtures`, `spec/rules`); the
test suite reads it directly. The JSON Schema is vendored into the package
(`src/_schema/`) and a test pins it byte-equal to the Kit's copy.
