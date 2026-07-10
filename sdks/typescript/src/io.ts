/**
 * Reading and emitting specs.
 *
 * `load()` mirrors the Java `WorkflowSpecReader`: strict parse → both validation
 * phases → return. A returned spec is valid by construction. `toJson()` emits the
 * canonical form (RFC 8785 + content rules) — the cross-SDK equivalence surface.
 */

import { canonicalString } from "./canonical.js";
import { type ValidationError, WorkflowValidationError } from "./errors.js";
import type { WorkflowSpec } from "./model.js";
import { DuplicateKeyError, parseStrict } from "./parse.js";
import { validateParsed } from "./validation.js";

/** JSON text → validated `WorkflowSpec` (both phases run before returning). */
export function load(text: string): WorkflowSpec {
  let parsed: unknown;
  try {
    parsed = parseStrict(text);
  } catch (err) {
    const message =
      err instanceof DuplicateKeyError
        ? err.message
        : err instanceof SyntaxError
          ? `not valid JSON: ${err.message}`
          : String(err);
    throw new WorkflowValidationError([{ code: "SCHEMA_INVALID", path: "$", message }]);
  }
  const errors: ValidationError[] = validateParsed(parsed);
  if (errors.length > 0) {
    throw new WorkflowValidationError(errors);
  }
  return parsed as WorkflowSpec;
}

/** Canonical UTF-8 JSON string of a spec (RFC 8785 + content rules) — the equivalence form. */
export function toJson(spec: WorkflowSpec): string {
  return canonicalString(spec);
}
