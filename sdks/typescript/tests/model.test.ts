/**
 * Model shape + type-guard behavior + strict-parse boundary through `load`.
 * (Construction-time invariants live in the binding helpers/validator, not the plain
 * data types — so these tests cover the load/emit boundary, not constructor guards.)
 */

import { describe, expect, it } from "vitest";

import {
  BINDING_PATTERN,
  isDecisionNode,
  isTaskNode,
  isTerminateNode,
  load,
  NODE_ID_PATTERN,
  toJson,
  WorkflowValidationError,
  type WorkflowSpec,
} from "../src/index.js";

const MINIMAL: WorkflowSpec = {
  apiVersion: "workflow/v2alpha",
  kind: "Workflow",
  metadata: { name: "t" },
  operations: { work: { ref: "java:test.work:v1" } },
  nodes: [
    { kind: "task", id: "do", operation: "work" },
    { kind: "terminate", id: "done", status: "completed" },
  ],
  edges: [{ from: "do", to: "done", when: { kind: "always" } }],
  entrypoint: "do",
};

describe("model", () => {
  it("emits and reloads to an equal model", () => {
    const json = toJson(MINIMAL);
    expect(load(json)).toEqual(MINIMAL);
  });

  it("type guards narrow the node union", () => {
    const [task, terminate] = MINIMAL.nodes;
    expect(isTaskNode(task!)).toBe(true);
    expect(isTerminateNode(terminate!)).toBe(true);
    expect(isDecisionNode(task!)).toBe(false);
  });

  it("frozen grammar constants match the schema", () => {
    // sanity: the model's binding pattern accepts the five §12 forms
    for (const ok of [
      "$input",
      "$input.url",
      "$context.pr.diff",
      "$const.threshold",
      "$node.fetch.output",
      "$node.route.decision",
    ]) {
      expect(BINDING_PATTERN.test(ok)).toBe(true);
    }
    for (const bad of ["$node.a.b.output", "$input.a.b", "$nope", "$node.x.result"]) {
      expect(BINDING_PATTERN.test(bad)).toBe(false);
    }
    expect(NODE_ID_PATTERN.test("a-b_1")).toBe(true);
    expect(NODE_ID_PATTERN.test("a.b")).toBe(false);
  });
});

describe("load strict-parse boundary", () => {
  it("rejects duplicate keys with SCHEMA_INVALID", () => {
    const doc = '{"apiVersion":"workflow/v2alpha","apiVersion":"workflow/v2alpha"}';
    try {
      load(doc);
      expect.unreachable("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(WorkflowValidationError);
      expect((err as WorkflowValidationError).errors[0]!.code).toBe("SCHEMA_INVALID");
      expect((err as WorkflowValidationError).errors[0]!.message).toContain("duplicate");
    }
  });

  it("reports malformed JSON as SCHEMA_INVALID", () => {
    try {
      load("{not json");
      expect.unreachable("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(WorkflowValidationError);
      expect((err as WorkflowValidationError).errors[0]!.code).toBe("SCHEMA_INVALID");
    }
  });
});
