/**
 * Canonical JSON Form (DD-15): RFC 8785 JCS via the `canonicalize` reference impl.
 *
 * The content rules (no nulls, absent-vs-empty, semantic array order) are emitter
 * obligations satisfied upstream: `undefined`-valued/omitted keys never reach the wire
 * (`canonicalize` drops them, matching the no-nulls rule), and a present-empty section
 * (`{}`) is preserved (canonical rule 4). JCS number formatting is native to JS —
 * `Number.prototype.toString` IS the ECMAScript algorithm RFC 8785 specifies — but we
 * use the reference library (DD-15's "don't reinvent JCS") rather than hand-rolling
 * key-sorting and string escaping.
 */

import canonicalize from "canonicalize";

/** RFC 8785 canonical JSON string of a JSON value. */
export function canonicalString(value: unknown): string {
  const out = canonicalize(value as object);
  if (out === undefined) {
    throw new Error("cannot canonicalize an undefined value");
  }
  return out;
}
