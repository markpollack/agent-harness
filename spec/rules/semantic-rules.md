# Semantic Rule Catalog — Workflow IR v2-alpha

> **Normative** (DD-13). This table is the single source of truth for phase-two
> (semantic) validation. Every SDK's semantic validator implements exactly these rules
> and reports exactly these error codes; the fixture corpus (`spec/fixtures/invalid/`)
> pins them. A validator that disagrees with this catalog is wrong; a rule change is a
> cross-SDK change and must update this file, the corpus, and all SDK validators in the
> same change.
>
> Phase one is JSON Schema validation against `spec/workflow-v2alpha.schema.json`
> (wire shape; rejection code `SCHEMA_INVALID`). Phase two below runs only on
> schema-valid documents. Validators MUST collect all violations, never stop at the
> first.
>
> **Path behavior** (applies to every rule): error paths are identifier-qualified —
> `nodes[id=route].outcomes`, `edges[from=route,to=done].when.value`,
> `operations[fetch-pr-diff].defaultPolicies.retry.backoff` — never bare array indices.
> Authors think in ids, not positions.

## Rules

| RULE_ID | applies_to | condition (violation) | error_code | path behavior |
|---------|------------|----------------------|------------|---------------|
| SEM-01 | nodes | two nodes share an `id` | `DUPLICATE_NODE_ID` | `nodes[id=<dup>]` |
| SEM-02 | edges | `from` or `to` references no existing node id | `EDGE_UNKNOWN_NODE` | `edges[from=<f>,to=<t>].from` / `.to` |
| SEM-03 | task, decision nodes | `operation` is not a key of the top-level `operations` map | `UNKNOWN_OPERATION` | `nodes[id=<id>].operation` |
| SEM-04 | decisionResult edges | edge `value` is not a declared outcome of the source node (a non-decision source declares no outcomes, so any decisionResult edge from one violates this rule) | `UNDECLARED_OUTCOME` | `edges[from=<f>,to=<t>].when.value` |
| SEM-05 | terminate nodes | terminate node has an outgoing edge | `TERMINATE_WITH_OUTGOING_EDGE` | `edges[from=<terminate>,to=<t>]` |
| SEM-06 | entrypoint | `entrypoint` references no existing node id | `UNKNOWN_ENTRYPOINT` | `entrypoint` |
| SEM-07 | node contextWrites | the same context key is written more than once within a single node (alpha spec §14) | `DUPLICATE_CONTEXT_WRITE` | `nodes[id=<id>].contextWrites.<key>` |
| SEM-08 | nodes | node is not reachable from `entrypoint` following edges (evaluated only when SEM-06 passes) | `UNREACHABLE_NODE` | `nodes[id=<id>]` |
| SEM-09 | decision nodes | a declared outcome has **no** decisionResult edge — guaranteed runtime edge-selection failure (§16) | `UNMATCHED_OUTCOME` | `nodes[id=<id>].outcomes` |
| SEM-10 | decision nodes | a declared outcome has **more than one** decisionResult edge — guaranteed runtime multi-match failure (§16) | `DUPLICATE_OUTCOME_EDGE` | `nodes[id=<id>].outcomes` |
| SEM-11 | edges | the graph contains a directed cycle. Alpha graphs are DAGs: checkpoint identity `(workflowRunId, nodeId)` presumes at-most-once node execution. **Increment-6 relaxation**: a single `decisionResult:"continue"` back-edge FROM a `loop` node into its body is legal (declared, bounded iteration, keyed `(runId, nodeId, iteration)`); all other cycles remain violations | `GRAPH_CYCLE` | `edges` (message carries one witness cycle `a -> b -> a`) |
| SEM-12 | all bindings (`input`, `contextWrites`, terminate `result`, workflow `outputs`) | a `$node.<id>...` binding references no existing node id | `BINDING_UNKNOWN_NODE` | `<owner path>.<binding key>` |
| SEM-13 | every policy attachment site (workflow `policies`, operation `defaultPolicies`, node `policies`) | `exponential` backoff without `multiplier`, or `initialMillis > maxMillis` when both present | `INVALID_BACKOFF` | `<site>.retry.backoff` |
| SEM-14 | node `input` bindings | the same input parameter is bound more than once within a single node | `DUPLICATE_BINDING_TARGET` | `nodes[id=<id>].input.<key>` |
| SEM-15 | open sections (`constants`, `types`, `contextSchema`, operation `inputSchema`/`outputSchema`) | a number whose magnitude exceeds the IEEE-754 safe integer range (2^53−1). Beyond this range Python (exact int), JS (rounded double), and Java (rounded double) diverge on the canonical form; forbidding it makes all three fail-closed on the same input (I-JSON). Also rejects large-magnitude floats — the only rule enforceable identically post-parse (JS/Java cannot distinguish a big-integer literal from a big float); extreme magnitudes belong in strings | `NUMBER_OUT_OF_RANGE` | `<section>.<path to the number>` |
| SEM-16 | `fork` nodes | the node's `join` names no `join` node | `FORK_UNKNOWN_JOIN` | `nodes[id=<id>].join` |
| SEM-17 | `join` nodes | a join is referenced by ≠ 1 fork's `join` field | `JOIN_ARITY` | `nodes[id=<id>]` |
| SEM-18 | `loop` nodes | the node lacks exactly one outgoing `decisionResult:"continue"` edge (the body back-edge) and one `decisionResult:"exit"` edge | `LOOP_EDGE_SHAPE` | `nodes[id=<id>]` |

> **Increment 6 (2026-07-13)** added SEM-15 (enforced now, all three SDKs + engine) and
> SEM-16/17/18 + the SEM-11 relaxation (fork/join/loop well-formedness — enforced as the
> Java model (6.2) and SDK models (6.5) gain the new node kinds; the rows are contract
> now so validators trace to them row-for-row).

## Implementation notes per language

- **SEM-07 / SEM-14 (duplicates in map-shaped sections)**: after JSON parsing these are
  unrepresentable in any language whose maps reject duplicate keys. The rules cover two
  surfaces, both mandatory: (a) **raw JSON with duplicate keys** — every conforming
  validator MUST detect duplicate keys at parse and reject the document (never silent
  last-wins). The Java reader uses strict duplicate-key detection (`SCHEMA_INVALID`);
  Python uses an `object_pairs_hook`; TypeScript must check during its own parse step
  (naive `JSON.parse` loses the duplicate and would wrongly accept the corpus's
  duplicate-key fixtures). (b) **builder APIs that accumulate writes as lists** before
  serialization — those MUST check and report `DUPLICATE_CONTEXT_WRITE` /
  `DUPLICATE_BINDING_TARGET` at build/validate time.
- **SEM-08 + SEM-11** operate on the edge set restricted to edges whose endpoints exist
  (SEM-02 already reports the others); SEM-08 runs only when SEM-06 passes.
- **SEM-12** extracts the node id as the segment between `$node.` and the next `.` (or
  end of string). The full binding path grammar is frozen in the alpha spec at Step 1.6;
  this rule checks node existence only.

## Deferred candidates (recorded, not rules in alpha)

- **Required-input coverage** (Flyte `ParameterNotBound`): needs the operation catalog
  (V2-Alpha-Plus #1) to know an operation's required inputs.
- **Binding ancestor ordering** (a `$node.x.output` binding where `x` is not an ancestor
  of the consuming node): dataflow analysis; revisit with the catalog.
- **Duplicate `always` edges from one node** (guaranteed multi-match on success):
  subsumed at runtime by §16; candidate for a static rule post-alpha.
- **Dead-end non-terminate node** (a task/decision node with zero outgoing edges —
  guaranteed zero-match failure on reaching it per §16): deliberately not a rule in
  alpha; the spec defines the runtime outcome (workflow fails deterministically) and a
  fail-by-default shape may be intentional. Revisit with real-workflow evidence.
- **`$node.<id>.decision` on a non-decision node**: resolved at the Step 1.6 grammar
  freeze as a *runtime* deterministic binding failure (alpha spec §12/§13), not a
  static rule; a static variant remains a post-alpha candidate.

## Revision history

| Date | Change |
|------|--------|
| 2026-07-06 | Initial catalog (core Step 1.4): SEM-01..SEM-14; Flyte-derived adoptions SEM-08/09/10/11; SEM-13 from the Kestra retry cross-field lesson |
