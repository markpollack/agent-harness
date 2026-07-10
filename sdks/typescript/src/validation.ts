/**
 * Two-phase validation (DD-13). Phase implementations land at Step T1.3.
 *
 * Phase one: JSON Schema (`spec/workflow-v2alpha.schema.json`) via ajv → `SCHEMA_INVALID`.
 * Phase two: the `spec/rules/semantic-rules.md` catalog (SEM-01…SEM-14) — the
 * Conformance Kit is the source of truth; the Java validator is a sibling, not the
 * reference.
 */

import type { ValidationError } from "./errors.js";

/** Both phases over a parsed JSON document; empty array = valid. */
export function validateParsed(_parsed: unknown): ValidationError[] {
  // T1.3 wires the schema and semantic phases here.
  return [];
}
