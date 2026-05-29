# Journal: Trace path wiring through AgentClientStep

**Date:** 2026-05-28  
**Scope:** agent-workflow, agent-client, agentworks BOM, docs, downstream consumers

## What we did

Wired trace file paths from `ClaudeAgentModel` (agent-client) through `AgentClientStep` into the workflow journal, enabling per-step JSONL trace files for Markov analysis, cost attribution, and debugging.

### Core changes (agent-workflow 0.8.0)

- Added `AgentClient.executeForResult()` — default method returning `ExecutionResult(text, tracePath)`. Backward compatible: lambdas work unchanged, trace path is null.
- `AgentClientStep` calls `executeForResult()`, stores `lastTracePath`, writes `TRACE_PATH` to `AgentContext` via `updateContext()`.
- `WorkflowExecutor` reads `TRACE_PATH` after `updateContext()`, passes to `StepTransition`, clears it so the next step doesn't inherit a stale path.
- `StepTransition` and `WorkflowStepEvent` carry optional `tracePath` field.
- `JdbcTraceRecorder` adds `trace_path` column.
- `AgentContext` gains `TRACE_PATH` well-known key and `Builder.without()`.
- `ClaudeStep` javadoc updated: quick scripts only, prefer `AgentClientStep` for trace capture.

### Design decision: immutable context

The handoff assumed `AgentContext` was mutable (`ctx.put(...)`). It's immutable — mutations return new instances. We adapted Option B:
- `AgentClientStep` stores trace path in a volatile field between `execute()` and `updateContext()`
- `updateContext()` produces a new context with `TRACE_PATH` set
- The executor reads it, records the transition, then clears it via `Builder.without()`

### Testing

- 6 unit tests: mock client with tracePath → context, lambda without tracePath, journal event with/without tracePath
- 1 integration test (`AgentClientStepTraceIT`): live Claude CLI → traceDir → JSONL file → trace path in `StepTransition`. Gated on `AGENT_CLIENT_IT=true`.
- All 363 existing tests pass.

### Releases

| Project | Version | Key change |
|---------|---------|------------|
| agent-client | 0.19.0 | `traceDir` on `ClaudeAgentModel` |
| agent-workflow | 0.8.0 | Trace path wiring through journal |
| agentworks BOM | 1.1.0 | workflow 0.8.0, agent-client 0.19.0 |
| agentworks BOM | 1.2.0 | acp-* 0.11.0 → 0.12.0 (staleness fix) |

### Docs

- New **Trace Capture** page on docs site
- Updated What's New, Getting Started, API Reference, Durability, BOM page

### Downstream updates

| Project | Change |
|---------|--------|
| agent-experiment-template | Migrated `WorkflowAgentInvoker` from `ClaudeStep` to `AgentClientStep`, bumped versions, updated CLAUDE.md |
| security-remediation-agent | Migrated both invokers to `AgentClientStep` with `traceDir` |
| bud-eval | BOM bump to 1.1.0 (then 1.2.0 via BOM update) |

### BOM staleness audit

Audited all 12 artifact families in the agentworks BOM. Found one stale entry: acp-* was at 0.11.0 when 0.12.0 had been released. Fixed in BOM 1.2.0. All other artifacts were current.

### Build-tools improvement

Updated both shared release workflows (`maven-central-release.yml`, `release-parent.yml`) in `markpollack/build-tools` to generate deterministic release notes from the commit log instead of GitHub's empty auto-generated notes.

## What's left

- Rerun the security-remediation-agent control experiment to verify trace files enable Markov analysis (separate session — needs target repo setup)
