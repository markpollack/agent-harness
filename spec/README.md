# Workflow IR v2 — Conformance Kit

This directory is the **language-neutral source of truth** for the Workflow IR v2-alpha
contract (DESIGN DD-9). No language owns it: the Java model, the Python SDK, and the
TypeScript SDK are all conforming consumers. Java embeds it via a build-time resource
copy; the SDK test suites read it directly and vendor the schema at packaging time.

**Status: AUTHORING CONTRACT FROZEN (Step 1.6, 2026-07-06). EVENT CONTRACT FROZEN
(Step 2.5, 2026-07-07).** The wire format (`workflow-v2alpha.schema.json`), the
semantic rule catalog and its error codes (`rules/semantic-rules.md` SEM-01…SEM-14 +
`SCHEMA_INVALID`), the Canonical JSON Form rules below, the fixture corpus, the event
contract (`events/workflow-event.schema.json` + the Event Contract section below), the
OperationResult wire projection (`operation-result.schema.json`), and the golden event
streams are all frozen for the v2-alpha cycle.

**Change control after the freeze**: any change to the schema, the fixture corpus, the
semantic rule catalog, or (post-2.5) the event contract is a **cross-SDK change** — it
must update this kit first and may not merge until every SDK's test suite (or, before an
SDK exists, its roadmap document) is updated in the same change.

**Evolution rules within `workflow/v2alpha`** (post-freeze changes that are ever
acceptable are additive only): new *optional* fields and new enum values may be added
with fixtures in the same change; field renames/removals and semantics changes require a
new `apiVersion`. Retired names are **reserved** — never reuse a removed field name or
error code with different meaning. Extension-point graduation follows DD-19: annotations
or attributes that become load-bearing are promoted to schema fields in the next
contract revision, never depended on in the bag.

## Layout

```
spec/
├── workflow-v2alpha.schema.json      # normative wire-shape schema (JSON Schema 2020-12)
├── operation-result.schema.json      # normative OperationResult wire projection (§6)
├── rules/semantic-rules.md           # normative phase-two rule catalog (stable error codes)
├── fixtures/
│   ├── valid/                        # specs that MUST pass both validation phases
│   └── invalid/                      # specs that MUST be rejected, with pinned codes
├── operation-results/
│   ├── valid/                        # result envelopes that MUST validate + round-trip
│   │                                 #   (incl. python-emitted-*/typescript-emitted-* —
│   │                                 #    the cross-language smoke fixtures, R7)
│   └── invalid/                      # result envelopes that MUST be schema-rejected
├── events/
│   ├── workflow-event.schema.json    # normative WorkflowEvent wire projection (§§8–11)
│   ├── *.events.json                 # golden event streams (deterministic projections)
│   └── fixtures/
│       ├── valid/                    # event envelopes that MUST validate
│       └── invalid/                  # event envelopes that MUST be schema-rejected
└── tools/                            # cross-language emitters for the smoke fixtures
```

## Two-phase validation

1. **Wire shape** — JSON Schema validation against `workflow-v2alpha.schema.json`.
   Rejection code: `SCHEMA_INVALID`.
2. **Semantic rules** — the catalog in `rules/semantic-rules.md` (SEM-01…SEM-14), each
   with a stable error code. Validators MUST collect all violations and report
   identifier-qualified paths.

Every SDK ships both phases (DD-13). The engine re-validates on load regardless of what
an SDK did (engine-side validation is the contract; SDK-side validation is DX).

## Fixture corpus format

- `fixtures/valid/*.json` — every fixture MUST pass both phases in every SDK, and MUST
  satisfy the round-trip law (below). `annotations.json` is the named DD-19 fixture:
  metadata- and node-level annotations MUST round-trip losslessly.
- `fixtures/invalid/<name>.json` + `fixtures/invalid/<name>.expected.json` — the fixture
  MUST be rejected, and the sidecar pins how:
  - `{"errorCodes": [...]}` — the set of **distinct** reported codes MUST equal exactly
    this set (order-insensitive; a rule may legitimately fire multiple times).
  - `{"anyOfCodes": [...]}` — at least one code MUST be reported and every reported code
    MUST be from this set. Used where conforming implementations may catch the problem
    at different layers (e.g. `duplicate-context-write`: raw-JSON duplicate keys are a
    parse-level `SCHEMA_INVALID` in implementations with strict duplicate detection, or
    a semantic `DUPLICATE_CONTEXT_WRITE` from builder-level list representations).

**Adding a fixture**: add the file (and sidecar for invalid ones), then run all three
SDK suites. A fixture change is by definition a cross-SDK event — the change-control
rule above applies. Invalid fixtures SHOULD trigger exactly the rule they are named for;
unavoidable co-occurring codes must be listed in the sidecar explicitly.

## Canonical JSON Form (normative — DESIGN DD-15)

Cross-SDK equivalence is **byte equality of canonicalized UTF-8**. Every SDK's
canonicalizer implements these rules:

1. **Base**: RFC 8785 (JSON Canonicalization Scheme) — lexicographically sorted object
   keys, ECMAScript number formatting, minimal string escaping, UTF-8.
2. **No nulls**: the wire format never emits `null`. Absent is the only empty state; a
   field with no value is omitted. SDK models must not distinguish "present-null" from
   "absent".
3. **Default elision**: an optional field whose value equals its documented default is
   omitted (alpha has no such defaults yet; the rule exists so evolution doesn't break
   equivalence).
4. **Empty containers**: optional top-level sections (`types`, `constants`,
   `contextSchema`, `policies`, `outputs`) are omitted when empty — this is an
   **emitter/builder obligation** (MUST, or two builders would emit different bytes for
   the same logical workflow), not a canonicalizer transformation: readers and writers
   preserve an explicitly-present empty section as-is on round-trip, and
   `canonicalize()` never drops it. Required sections (`metadata`, `operations`,
   `nodes`, `edges`) are always present.
5. **Array order is semantic**: `nodes` and `edges` preserve authoring declaration
   order; canonicalization MUST NOT sort arrays. Builders must emit arrays
   deterministically (declaration order).
6. **Maps are unordered**: `operations`, `constants`, `labels`, `annotations`, `input`,
   `contextWrites`, `outputs` are canonicalized by JCS key sorting.
7. **Execution-order alignment**: because JCS sorts map keys, "declaration order" is not
   observable for map-shaped sections on the canonical wire. Therefore deterministic
   execution order for `contextWrites` application and `input` binding evaluation is
   **lexicographic key order** — where "lexicographic" means **RFC 8785 key order**
   (UTF-16 code-unit comparison) in every SDK; do not substitute code-point or locale
   collation (they diverge for non-BMP keys).

Notes:
- **Numbers** follow RFC 8785's ECMAScript serialization: `1.0` canonicalizes to `1`,
  `1e2` to `100`, `0.8` stays `0.8`. Emitters may write any valid JSON number; the
  canonical form is what equivalence compares.
- **Duplicate keys** in raw JSON are invalid input: parsers MUST NOT silently
  last-wins (see `rules/semantic-rules.md` SEM-07/SEM-14 implementation notes).

**Round-trip law**: for every valid spec `j`:
`write(read(j))` is byte-equal to `canonicalize(j)`, and reading back the written form
yields an equal model. The Java corpus test asserts this for every valid fixture; SDK
suites must do the same.

## Event Contract (normative — frozen at Step 2.5)

The full semantics live in the alpha spec §§8–11; this section pins the conformance
surface every SDK and interpreter implements. `events/workflow-event.schema.json` is
the executable shape.

**Event types (14, closed for v2-alpha)**: `WorkflowStarted`, `NodeStarted`,
`OperationDispatched`, `OperationSucceeded`, `OperationFailed`, `RetryScheduled`,
`BindingEvaluated`, `ContextWriteApplied`, `EdgeSelected`, `NodeCompleted`,
`WorkflowCompleted`, `WorkflowFailed`, `WorkflowCancelled`, `WorkflowAborted`.
Terminal workflow states are the closed set `completed | failed | cancelled | aborted`,
each with exactly one terminal event; a run-level `paused` state is reserved without
an event type. Wire names are exactly these CamelCase forms; OperationResult states use
the lowercase §6 forms (`success`, `timed_out`, …).

**Envelope**: required `eventType`, `workflowRunId`, `workflowSpecRef`, `sequence`,
`timestamp`; identity fields are class-scoped and strict — workflow-level events carry
no node/operation/attempt fields, node-level events carry `nodeId` only, attempt-level
events carry all three. Sequences are **1-based**, monotonic per run; `0` is reserved
as the "no event committed yet" sentinel.

**Payloads**: per-type required keys and closed vocabularies are in the schema (§9 of
the alpha spec is the prose form). Vocabularies pinned: `BindingEvaluated.status` ∈
`success|failure`; `NodeCompleted.state` ∈ `succeeded|failed|cancelled|aborted`
(terminate nodes report `succeeded`); `EdgeSelected.reason` ∈
`always|decision_outcome_match|error_match`; `OperationFailed.resultState` ∈
`failure|timed_out`. Usage/cost metrics (`usage{tokens?, costUsd?}`) ride on operation
terminal events, projected verbatim from the OperationResult. Evidence-ledger fields:
optional `executorId` on attempt payloads; optional `scheduledFor` on post-retry
`OperationDispatched`; attempt duration is derived from timestamps, never carried.

**Extension point**: the envelope `attributes` bag (DD-19) — dotted-namespace keys,
ignorable, never control-flow-relevant, disclosure rules apply to values, excluded from
conformance comparison. Graduation: a load-bearing attribute becomes a schema field in
the next revision.

**Disclosure**: canonical events never carry raw payload values; the `valueDisclosure`
forms (`metadata_only`, `hmac_hash`) are frozen in the event schema. `type` uses
JSON-family names; `sizeBytes` is UTF-8 length, reported for strings in v2-alpha.

**Deterministic projection** (what golden streams compare): per event — `sequence`,
`eventType`, `nodeId?`, `operationRef?`, `attemptNumber?`, `payload?` minus
`scheduledFor`; excluded — `timestamp`, `attributes`, `workflowRunId`,
`workflowSpecRef` (the last two are carried once by the stream document
`{spec, workflowRunId, events}`). Comparison is canonical byte equality (RFC 8785) of
the projection document, never host-language tree equality.

**Golden streams** (`events/*.events.json`): `golden-pr-review` (success path, includes
a usage-bearing OperationSucceeded), `golden-pr-review-fail-path` (decision routing to
the failed terminate), `golden-pr-review-binding-failure`, `error-edge-routing` (three
attempts, two retries, error-edge recovery), `retry-exhaustion-unroutable`, and
`operation-cancelled` (NodeCompleted state mirrors the terminal event type). A conforming
interpreter given the named spec fixture, the pinned `workflowRunId`, the documented
deterministic handler outputs, and the documented input MUST reproduce each stream's
projection byte-for-byte. Golden conformance runs with `executorId` unset — it is
environment identity and participates in the projection when present, so conformance
runners must not set one.

**Golden-stream update rule**: any event change — a payload key, an ordering rule, a
vocabulary value — changes golden streams and is therefore a cross-SDK conformance
change under the change-control rule above. Regeneration is deliberate:
`./mvnw -pl workflow-engine -am test -Dtest=GoldenEventStreamTest
-Dspec.events.regenerate=true -Dsurefire.failIfNoSpecifiedTests=false`, then review the
diff as a contract diff.

**Ordering**: guaranteed vs incidental ordering and the multi-attempt supersession rule
are normative in alpha spec §10 — consumers MUST NOT depend on cross-node interleaving
beyond the per-node subsequences; parallel execution (post-alpha) will interleave them.

## Freeze declarations

- **Authoring contract** (schema, semantic rules, canonical form, error codes):
  **FROZEN 2026-07-06** (core Step 1.6). Reconciled artifacts: alpha spec doc
  (`plans/v2/WORKFLOW-IR-V2-ALPHA-SPEC.md`, 2026-07-06 revision), schema, Java model,
  fixture corpus — zero known discrepancies at freeze time; the corpus test suite is
  the executable proof.
- **Event contract** (`events/`, `operation-result.schema.json`, Event Contract section
  above): **FROZEN 2026-07-07** (core Step 2.5; QA-hardened same day at Step 2.K).
  Reconciled artifacts: alpha spec doc §§6, 8–11 (2026-07-07 revision), both
  wire-projection schemas, the engine event model/factory/interpreter, six golden event
  streams, and the wire-fixture corpora (operation-results: 13 valid incl. 2
  cross-language-emitted / 9 invalid; events: 17 valid / 13 invalid) — zero known
  discrepancies at freeze time; `WireSchemaConformanceTest` + `GoldenEventStreamTest` +
  `LiveEventEnvelopeConformanceTest` (every live interpreter envelope on every path,
  golden-covered or not, validates against the event schema) are the executable proof.
  The cross-language envelope smoke test (R7) passes: `tools/emit_operation_result.py`
  and `tools/emit-operation-result.mjs` outputs schema-validate and round-trip through
  the Java codec to canonical byte equality.
