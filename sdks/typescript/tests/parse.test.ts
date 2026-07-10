/**
 * The strict parser: matches `JSON.parse` on valid input, rejects duplicate keys
 * (including escape-equivalent keys) that `JSON.parse` would silently collapse.
 */

import { describe, expect, it } from "vitest";

import { DuplicateKeyError, parseStrict } from "../src/index.js";
import { readFixture, VALID_FIXTURES } from "./corpus.js";

describe("parseStrict", () => {
  it.each(VALID_FIXTURES)("$name parses identically to JSON.parse", ({ path }) => {
    const raw = readFixture(path);
    expect(parseStrict(raw)).toEqual(JSON.parse(raw));
  });

  it("accepts distinct keys and nested objects/arrays", () => {
    const text = '{"a":1,"b":{"c":2,"d":[{"e":3},{"e":4}]},"f":"g"}';
    expect(parseStrict(text)).toEqual(JSON.parse(text));
  });

  it("rejects top-level duplicate keys", () => {
    expect(() => parseStrict('{"a":1,"a":2}')).toThrow(DuplicateKeyError);
  });

  it("rejects nested duplicate keys", () => {
    expect(() => parseStrict('{"outer":{"k":1,"k":2}}')).toThrow(DuplicateKeyError);
  });

  it("rejects escape-equivalent duplicate keys (JSON.parse would collapse them)", () => {
    // "a" and "a" are the same key in JSON semantics
    expect(() => parseStrict('{"a":1,"\\u0061":2}')).toThrow(DuplicateKeyError);
  });

  it("does not treat equal strings in different objects as duplicates", () => {
    const text = '{"x":{"k":1},"y":{"k":2}}';
    expect(parseStrict(text)).toEqual(JSON.parse(text));
  });

  it("does not treat a value string equal to a key as a duplicate", () => {
    const text = '{"k":"k"}';
    expect(parseStrict(text)).toEqual(JSON.parse(text));
  });

  it("handles strings containing braces and colons without miscounting nesting", () => {
    const text = '{"a":"{not:an,object}","a2":"[1,2]"}';
    expect(parseStrict(text)).toEqual(JSON.parse(text));
  });

  it("is not fooled by a value string that mimics a key:value injection", () => {
    // the value contains `","x":2` — the scanner must treat it as one string, not
    // a second `x` key (else it would false-positive a duplicate)
    const text = '{"x":"\\",\\"x\\":2","y":3}';
    expect(parseStrict(text)).toEqual(JSON.parse(text));
  });

  it("detects a duplicate key with an escaped quote in the key", () => {
    expect(() => parseStrict('{"a\\"b":1,"a\\"b":2}')).toThrow(DuplicateKeyError);
  });

  it("propagates malformed JSON as a SyntaxError", () => {
    expect(() => parseStrict("{not json")).toThrow(SyntaxError);
  });
});
