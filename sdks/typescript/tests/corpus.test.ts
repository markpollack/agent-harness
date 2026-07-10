/**
 * Corpus conformance (DD-9/DD-13): the Conformance Kit is the executable contract.
 * Every valid fixture passes both phases; every invalid fixture reproduces its
 * sidecar-pinned codes (`errorCodes` = exact distinct set; `anyOfCodes` = non-empty
 * subset).
 */

import { describe, expect, it } from "vitest";

import { load, parseStrict, WorkflowValidationError } from "../src/index.js";
import { validateParsed } from "../src/validation.js";
import {
  INVALID_FIXTURES,
  readExpected,
  readFixture,
  readSchema,
  SPEC_DIR,
  VALID_FIXTURES,
} from "./corpus.js";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/** The distinct error codes the SDK reports for a document (parse or validation). */
function collectedCodes(raw: string): Set<string> {
  let parsed: unknown;
  try {
    parsed = parseStrict(raw);
  } catch (err) {
    if (err instanceof WorkflowValidationError) {
      return new Set(err.errors.map((e) => e.code));
    }
    // parse-level (duplicate key / malformed) surfaces as SCHEMA_INVALID via load
    try {
      load(raw);
    } catch (loadErr) {
      if (loadErr instanceof WorkflowValidationError) {
        return new Set(loadErr.errors.map((e) => e.code));
      }
    }
    throw err;
  }
  return new Set(validateParsed(parsed).map((e) => e.code));
}

describe("valid corpus passes both phases", () => {
  it.each(VALID_FIXTURES)("$name", ({ path }) => {
    expect([...collectedCodes(readFixture(path))]).toEqual([]);
    load(readFixture(path)); // and binds
  });
});

describe("invalid corpus reproduces pinned codes", () => {
  it.each(INVALID_FIXTURES)("$name", ({ path }) => {
    const sidecar = readExpected(path);
    const codes = collectedCodes(readFixture(path));

    if (sidecar.errorCodes) {
      expect([...codes].sort()).toEqual([...sidecar.errorCodes].sort());
    } else {
      const allowed = new Set(sidecar.anyOfCodes);
      expect(codes.size).toBeGreaterThan(0);
      for (const c of codes) expect(allowed.has(c)).toBe(true);
    }
  });
});

describe("conformance-kit integrity", () => {
  it("vendored schema is byte-equal to the Conformance Kit", () => {
    const vendored = readFileSync(
      join(SPEC_DIR, "..", "sdks", "typescript", "src", "_schema", "workflow-v2alpha.schema.json"),
      "utf-8",
    );
    expect(vendored).toBe(readSchema());
  });

  it("semantic errors are collected, not first-wins", () => {
    const doc = {
      apiVersion: "workflow/v2alpha",
      kind: "Workflow",
      metadata: { name: "multi-error" },
      operations: { work: { ref: "java:test.work:v1" } },
      nodes: [
        { id: "do", kind: "task", operation: "missing-op" },
        { id: "done", kind: "terminate", status: "completed" },
      ],
      edges: [{ from: "do", to: "done", when: { kind: "always" } }],
      entrypoint: "nope",
    };
    const codes = new Set(validateParsed(doc).map((e) => e.code));
    expect(codes).toEqual(new Set(["UNKNOWN_OPERATION", "UNKNOWN_ENTRYPOINT"]));
  });

  it("error paths are identifier-qualified", () => {
    const doc = {
      apiVersion: "workflow/v2alpha",
      kind: "Workflow",
      metadata: { name: "paths" },
      operations: { work: { ref: "java:test.work:v1" } },
      nodes: [
        { id: "do", kind: "task", operation: "missing-op" },
        { id: "done", kind: "terminate", status: "completed" },
      ],
      edges: [{ from: "do", to: "done", when: { kind: "always" } }],
      entrypoint: "do",
    };
    const errors = validateParsed(doc);
    expect(errors).toHaveLength(1);
    expect(errors[0]!.path).toBe("nodes[id=do].operation");
  });
});
