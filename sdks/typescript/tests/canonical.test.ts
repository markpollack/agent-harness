/**
 * Canonicalization edge cases beyond the fixture corpus (T1.K QA).
 *
 * The frozen contract bounds every *typed* numeric field ≤ 2^53-1, so these cases
 * only reach OPEN sections (`constants`, `types`, schemas). They pin TS's canonical
 * output against the **JVM reference** (`CanonicalJson`, the engine's canonicalizer,
 * which the wire form is defined by) — verified equal by direct cross-check at T1.K.
 *
 * Note (recorded, not a TS defect): Python's `rfc8785` REJECTS out-of-2^53 integers
 * (`IntegerDomainError`) where Java and TS both accept-and-emit them. TS matches the
 * reference; the Python over-strictness is a separate cross-SDK/contract item.
 */

import { describe, expect, it } from "vitest";

import { canonicalString } from "../src/index.js";

describe("canonicalization number edge cases (open-section values)", () => {
  it("emits out-of-safe-range integers verbatim, matching the JVM reference", () => {
    // JVM CanonicalJson: {"h":100000000000000000000}
    expect(canonicalString({ h: 100000000000000000000 })).toBe('{"h":100000000000000000000}');
  });

  it("formats large/small magnitudes per ECMAScript, matching the JVM reference", () => {
    // JVM: 1e21 -> 1e+21 ; 1e-7 -> 1e-7
    expect(canonicalString({ d: 1e21 })).toBe('{"d":1e+21}');
    expect(canonicalString({ e: 1e-7 })).toBe('{"e":1e-7}');
  });

  it("normalizes integral floats and preserves 2^53-1 exactly", () => {
    expect(canonicalString({ a: 1.0, b: 1e2, g: 9007199254740991 })).toBe(
      '{"a":1,"b":100,"g":9007199254740991}',
    );
  });

  it("sorts keys by UTF-16 code unit incl. non-BMP and digits", () => {
    expect(canonicalString({ "☃": 1, Z: 2, a: 3, _: 4, "10": 5, "2": 6 })).toBe(
      '{"10":5,"2":6,"Z":2,"_":4,"a":3,"☃":1}',
    );
  });
});
