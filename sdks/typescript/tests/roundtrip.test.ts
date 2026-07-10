/**
 * The round-trip law over the valid corpus (spec/README.md):
 * for every valid spec j: `toJson(load(j))` equals `canonicalize(j)`, and reading
 * back the written form yields an equal model.
 */

import canonicalize from "canonicalize";
import { describe, expect, it } from "vitest";

import { load, toJson } from "../src/index.js";
import { readFixture, VALID_FIXTURES } from "./corpus.js";

describe("round-trip law (valid corpus)", () => {
  it.each(VALID_FIXTURES)("$name round-trips to canonical bytes", ({ path }) => {
    const raw = readFixture(path);
    const spec = load(raw);

    const written = toJson(spec);
    const canonicalInput = canonicalize(JSON.parse(raw));

    expect(written).toBe(canonicalInput);

    const reread = load(written);
    expect(reread).toEqual(spec);
  });

  it("annotations survive losslessly (DD-19)", () => {
    const fixture = VALID_FIXTURES.find((f) => f.name === "annotations")!;
    const spec = load(readFixture(fixture.path));

    expect(spec.metadata.annotations).toBeTruthy();
    const annotatedNodes = spec.nodes.filter((n) => n.annotations);
    expect(annotatedNodes.length).toBeGreaterThan(0);
    expect(load(toJson(spec))).toEqual(spec);
  });

  it("empty-sections preserves present-but-empty sections (rule 4)", () => {
    const fixture = VALID_FIXTURES.find((f) => f.name === "empty-sections")!;
    const spec = load(readFixture(fixture.path));

    expect(spec.types).toEqual({});
    expect(spec.constants).toEqual({});
    expect(spec.contextSchema).toEqual({});
    expect(spec.outputs).toEqual({});

    const wire = JSON.parse(toJson(spec));
    expect(wire.types).toEqual({});
    expect(wire.outputs).toEqual({});
  });
});
