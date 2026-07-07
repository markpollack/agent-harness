# Workflow IR v2 — Conformance Kit

This directory is the **language-neutral source of truth** for the Workflow IR v2-alpha
contract (DESIGN DD-9). No language owns it: the Java model, the Python SDK, and the
TypeScript SDK are all conforming consumers. Java embeds it via a build-time resource
copy; the SDK test suites read it directly and vendor the schema at packaging time.

**Status: PRE-FREEZE.** The authoring contract freezes at core roadmap Step 1.6; the
event contract freezes at Step 2.5. Until the corresponding freeze is declared in this
file, artifacts here may change without cross-SDK coordination. After a freeze, **any
change to the schema, the fixture corpus, the semantic rule catalog, or (post-2.5) the
event contract is a cross-SDK change**: it must update this kit first and may not merge
until every SDK's test suite (or, before an SDK exists, its roadmap document) is updated
in the same change.

## Layout

```
spec/
├── workflow-v2alpha.schema.json   # normative wire-shape schema (JSON Schema 2020-12)
├── rules/semantic-rules.md        # normative phase-two rule catalog (stable error codes)
├── fixtures/
│   ├── valid/                     # specs that MUST pass both validation phases
│   └── invalid/                   # specs that MUST be rejected, with pinned codes
└── events/                        # golden event streams + wire projections (from Step 2.4/2.5)
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
   `contextSchema`, `policies`, `outputs`) are omitted when empty; required sections
   (`metadata`, `operations`, `nodes`, `edges`) are always present.
5. **Array order is semantic**: `nodes` and `edges` preserve authoring declaration
   order; canonicalization MUST NOT sort arrays. Builders must emit arrays
   deterministically (declaration order).
6. **Maps are unordered**: `operations`, `constants`, `labels`, `annotations`, `input`,
   `contextWrites`, `outputs` are canonicalized by JCS key sorting.
7. **Execution-order alignment**: because JCS sorts map keys, "declaration order" is not
   observable for map-shaped sections on the canonical wire. Therefore deterministic
   execution order for `contextWrites` application and `input` binding evaluation is
   **lexicographic key order**. (The alpha spec text is reconciled to this wording at
   Step 1.6.)

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

## Freeze declarations

- **Authoring contract** (schema, semantic rules, canonical form, error codes):
  _not yet frozen — declared here at Step 1.6._
- **Event contract** (`events/`): _not yet frozen — declared here at Step 2.5._
