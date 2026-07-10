/** Structured validation errors (DD-13: codes + identifier-qualified paths). */

/**
 * One violation: a stable error code, an identifier-qualified path, a message.
 *
 * Codes come from the Conformance Kit: `SCHEMA_INVALID` for wire-shape (phase one)
 * and the `spec/rules/semantic-rules.md` catalog codes for phase two. Paths use
 * identifiers, not indices (`nodes[id=route].outcomes`).
 */
export interface ValidationError {
  readonly code: string;
  readonly path: string;
  readonly message: string;
}

/** Thrown when a spec fails validation; carries every collected violation. */
export class WorkflowValidationError extends Error {
  readonly errors: readonly ValidationError[];

  constructor(errors: readonly ValidationError[]) {
    const summary = errors.map((e) => `${e.code} at ${e.path}: ${e.message}`).join("; ");
    super(`workflow spec validation failed (${errors.length}): ${summary}`);
    this.name = "WorkflowValidationError";
    this.errors = errors;
  }
}
