#!/usr/bin/env bash
# Repo-level cross-SDK conformance gate (ROADMAP.md § The Roadmap Set).
#
# The CI-INDEPENDENT materialization of the merge gate: runs every suite that a
# spec/ change must not break, in one command, from a clean checkout. The GitHub
# Actions workflows are main-branch-filtered by repo convention (no CI on v2 for
# now, per project decision 2026-07-08), so on the working branch THIS SCRIPT is the
# mechanical safeguard that the "no spec/ change lands without every suite green"
# guarantee actually holds — not manual discipline alone.
#
# Cross-SDK EQUIVALENCE itself is enforced transitively: the Java emitter and each
# SDK compare their output against the SAME frozen fixture bytes in their own suite,
# so a canonical-form drift breaks that suite. This script closes the ORCHESTRATION
# gap — it forces all three to run together.
#
# Usage:
#   scripts/conformance-gate.sh            # run every available suite
#   FAST=1 scripts/conformance-gate.sh     # skip the Failsafe ITs (surefire only)
#
# Exit non-zero if any suite fails or is unexpectedly missing its toolchain.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0
run() { # label, dir, command...
  local label="$1" dir="$2"; shift 2
  echo "══════════════════════════════════════════════════════════════════"
  echo "▶ ${label}"
  echo "══════════════════════════════════════════════════════════════════"
  if ( cd "$dir" && "$@" ); then
    echo "✔ ${label}"
  else
    echo "✖ ${label} FAILED"
    fail=1
  fi
}

# 1. Java: model + engine + fixture corpus + golden streams + emitter byte-equality
if [[ "${FAST:-0}" == "1" ]]; then
  run "Java (./mvnw test)" "$ROOT" ./mvnw -q test
else
  run "Java (./mvnw verify — incl. Failsafe ITs)" "$ROOT" ./mvnw -q verify
fi

# 2. Python SDK: model + canonical round-trip + corpus + equivalence
if [[ -d sdks/python ]]; then
  if command -v uv >/dev/null 2>&1; then
    run "Python SDK (uv run pytest)" "$ROOT/sdks/python" uv run pytest -q
  else
    echo "⚠ uv not found — SKIPPING Python SDK suite (install uv to close the gate)"
    fail=1
  fi
fi

# 3. TypeScript SDK: model + canonical round-trip + corpus + equivalence
if [[ -d sdks/typescript ]]; then
  if command -v pnpm >/dev/null 2>&1; then
    run "TypeScript SDK (pnpm test)" "$ROOT/sdks/typescript" pnpm -s test
  else
    echo "⚠ pnpm not found — SKIPPING TypeScript SDK suite (install pnpm to close the gate)"
    fail=1
  fi
fi

echo "══════════════════════════════════════════════════════════════════"
if [[ "$fail" == "0" ]]; then
  echo "✔ CONFORMANCE GATE PASSED — all available SDK suites green"
else
  echo "✖ CONFORMANCE GATE FAILED — see above"
fi
exit "$fail"
