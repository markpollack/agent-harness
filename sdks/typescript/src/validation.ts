/**
 * Two-phase validation (DD-13).
 *
 * Phase one: JSON Schema (ajv Draft 2020-12) against the vendored
 * `workflow-v2alpha.schema.json` (byte-equal to the Conformance Kit's — a test pins
 * the copy) → `SCHEMA_INVALID`.
 * Phase two: the `spec/rules/semantic-rules.md` catalog, SEM-01…SEM-14 — the Kit is
 * the source of truth; the Java validator is a sibling implementation, not the
 * reference. Violations are COLLECTED (never first-wins) with identifier-qualified
 * paths (`nodes[id=route].outcomes`).
 *
 * Catalog-pinned suppressions: SEM-08 (reachability) runs only when SEM-06
 * (entrypoint) passes; SEM-08/SEM-11 (cycles) operate on the edge subset whose
 * endpoints exist. SEM-07/SEM-14's raw-JSON surface is the parse-level duplicate-key
 * rejection (`parse.ts`); their builder surface is the Stage-T2 builder's job.
 */

import Ajv2020, { type ValidateFunction } from "ajv/dist/2020.js";

import type { ValidationError } from "./errors.js";
import {
  type Backoff,
  type Edge,
  isDecisionNode,
  isTaskNode,
  isTerminateNode,
  type Node,
  type PolicyBundle,
  type WorkflowSpec,
} from "./model.js";
import schema from "./_schema/workflow-v2alpha.schema.json" with { type: "json" };

const ajv = new Ajv2020({ allErrors: true, strict: false });
const validateSchema: ValidateFunction = ajv.compile(schema);

/** Phase one: wire shape. Every violation reports `SCHEMA_INVALID`. */
export function schemaValidate(parsed: unknown): ValidationError[] {
  if (validateSchema(parsed)) return [];
  return (validateSchema.errors ?? []).map((e) => ({
    code: "SCHEMA_INVALID",
    path: e.instancePath === "" ? "$" : `$${e.instancePath}`,
    message: e.message ?? "schema violation",
  }));
}

/**
 * Both phases over a parsed JSON document; empty array = valid. Phase two runs only
 * on schema-valid documents (catalog preamble), mirroring the Java reader.
 */
export function validateParsed(parsed: unknown): ValidationError[] {
  const schemaErrors = schemaValidate(parsed);
  if (schemaErrors.length > 0) return schemaErrors;
  return validate(parsed as WorkflowSpec);
}

/** Phase two: the semantic rule catalog over a bound model. */
export function validate(spec: WorkflowSpec): ValidationError[] {
  const errors: ValidationError[] = [];
  const nodeIds = new Set(spec.nodes.map((n) => n.id));

  sem01DuplicateNodeIds(spec.nodes, errors);
  sem02EdgeEndpoints(spec.edges, nodeIds, errors);
  sem03UnknownOperations(spec, errors);
  sem04UndeclaredOutcomes(spec, errors);
  sem05TerminateOutgoing(spec, errors);
  const entrypointOk = sem06Entrypoint(spec, nodeIds, errors);

  const validEdges = spec.edges.filter((e) => nodeIds.has(e.from) && nodeIds.has(e.to));
  if (entrypointOk) sem08Reachability(spec, validEdges, errors);
  sem0910OutcomeCoverage(spec, errors);
  sem11Cycles(validEdges, errors);
  sem12BindingNodeRefs(spec, nodeIds, errors);
  sem13Backoff(spec, errors);
  return errors;
}

// ---------------------------------------------------------------------------
// Rules
// ---------------------------------------------------------------------------

function sem01DuplicateNodeIds(nodes: readonly Node[], errors: ValidationError[]): void {
  const seen = new Set<string>();
  const reported = new Set<string>();
  for (const node of nodes) {
    if (seen.has(node.id) && !reported.has(node.id)) {
      reported.add(node.id);
      errors.push({
        code: "DUPLICATE_NODE_ID",
        path: `nodes[id=${node.id}]`,
        message: `two nodes share the id '${node.id}'`,
      });
    }
    seen.add(node.id);
  }
}

function sem02EdgeEndpoints(
  edges: readonly Edge[],
  nodeIds: Set<string>,
  errors: ValidationError[],
): void {
  for (const edge of edges) {
    for (const [endpoint, value] of [
      ["from", edge.from],
      ["to", edge.to],
    ] as const) {
      if (!nodeIds.has(value)) {
        errors.push({
          code: "EDGE_UNKNOWN_NODE",
          path: `edges[from=${edge.from},to=${edge.to}].${endpoint}`,
          message: `edge ${endpoint} references unknown node '${value}'`,
        });
      }
    }
  }
}

function sem03UnknownOperations(spec: WorkflowSpec, errors: ValidationError[]): void {
  for (const node of spec.nodes) {
    if ((isTaskNode(node) || isDecisionNode(node)) && !(node.operation in spec.operations)) {
      errors.push({
        code: "UNKNOWN_OPERATION",
        path: `nodes[id=${node.id}].operation`,
        message: `operation '${node.operation}' is not declared in operations`,
      });
    }
  }
}

function sem04UndeclaredOutcomes(spec: WorkflowSpec, errors: ValidationError[]): void {
  const byId = new Map(spec.nodes.map((n) => [n.id, n]));
  for (const edge of spec.edges) {
    if (edge.when.kind !== "decisionResult") continue;
    const source = byId.get(edge.from);
    if (source === undefined) continue;
    const outcomes = isDecisionNode(source) ? source.outcomes : [];
    if (!outcomes.includes(edge.when.value)) {
      errors.push({
        code: "UNDECLARED_OUTCOME",
        path: `edges[from=${edge.from},to=${edge.to}].when.value`,
        message: `'${edge.when.value}' is not a declared outcome of '${edge.from}'`,
      });
    }
  }
}

function sem05TerminateOutgoing(spec: WorkflowSpec, errors: ValidationError[]): void {
  const terminateIds = new Set(spec.nodes.filter(isTerminateNode).map((n) => n.id));
  for (const edge of spec.edges) {
    if (terminateIds.has(edge.from)) {
      errors.push({
        code: "TERMINATE_WITH_OUTGOING_EDGE",
        path: `edges[from=${edge.from},to=${edge.to}]`,
        message: `terminate node '${edge.from}' must have no outgoing edges`,
      });
    }
  }
}

function sem06Entrypoint(
  spec: WorkflowSpec,
  nodeIds: Set<string>,
  errors: ValidationError[],
): boolean {
  if (!nodeIds.has(spec.entrypoint)) {
    errors.push({
      code: "UNKNOWN_ENTRYPOINT",
      path: "entrypoint",
      message: `entrypoint references unknown node '${spec.entrypoint}'`,
    });
    return false;
  }
  return true;
}

function sem08Reachability(
  spec: WorkflowSpec,
  validEdges: Edge[],
  errors: ValidationError[],
): void {
  const adjacency = new Map<string, string[]>();
  for (const edge of validEdges) {
    (adjacency.get(edge.from) ?? adjacency.set(edge.from, []).get(edge.from)!).push(edge.to);
  }
  const reachable = new Set<string>();
  const stack = [spec.entrypoint];
  while (stack.length > 0) {
    const current = stack.pop()!;
    if (reachable.has(current)) continue;
    reachable.add(current);
    stack.push(...(adjacency.get(current) ?? []));
  }
  for (const node of spec.nodes) {
    if (!reachable.has(node.id)) {
      errors.push({
        code: "UNREACHABLE_NODE",
        path: `nodes[id=${node.id}]`,
        message: `node '${node.id}' is not reachable from the entrypoint`,
      });
    }
  }
}

function sem0910OutcomeCoverage(spec: WorkflowSpec, errors: ValidationError[]): void {
  for (const node of spec.nodes) {
    if (!isDecisionNode(node)) continue;
    const counts = new Map<string, number>(node.outcomes.map((o) => [o, 0]));
    for (const edge of spec.edges) {
      if (edge.from === node.id && edge.when.kind === "decisionResult") {
        const v = edge.when.value;
        if (counts.has(v)) counts.set(v, counts.get(v)! + 1);
      }
    }
    for (const [outcome, count] of counts) {
      if (count === 0) {
        errors.push({
          code: "UNMATCHED_OUTCOME",
          path: `nodes[id=${node.id}].outcomes`,
          message: `declared outcome '${outcome}' has no decisionResult edge (§16)`,
        });
      } else if (count > 1) {
        errors.push({
          code: "DUPLICATE_OUTCOME_EDGE",
          path: `nodes[id=${node.id}].outcomes`,
          message: `declared outcome '${outcome}' has ${count} decisionResult edges (§16 requires exactly one)`,
        });
      }
    }
  }
}

function sem11Cycles(validEdges: Edge[], errors: ValidationError[]): void {
  const adjacency = new Map<string, string[]>();
  for (const edge of validEdges) {
    (adjacency.get(edge.from) ?? adjacency.set(edge.from, []).get(edge.from)!).push(edge.to);
  }
  const WHITE = 0,
    GRAY = 1,
    BLACK = 2;
  const color = new Map<string, number>();
  const path: string[] = [];

  const visit = (node: string): string[] | null => {
    color.set(node, GRAY);
    path.push(node);
    for (const succ of adjacency.get(node) ?? []) {
      const state = color.get(succ) ?? WHITE;
      if (state === GRAY) {
        return [...path.slice(path.indexOf(succ)), succ];
      }
      if (state === WHITE) {
        const witness = visit(succ);
        if (witness) return witness;
      }
    }
    path.pop();
    color.set(node, BLACK);
    return null;
  };

  for (const start of adjacency.keys()) {
    if ((color.get(start) ?? WHITE) === WHITE) {
      const witness = visit(start);
      if (witness) {
        errors.push({
          code: "GRAPH_CYCLE",
          path: "edges",
          message: `alpha graphs are DAGs; cycle: ${witness.join(" -> ")}`,
        });
        return;
      }
    }
  }
}

function eachBinding(spec: WorkflowSpec): Array<{ path: string; from: string }> {
  const sites: Array<{ path: string; from: string }> = [];
  const push = (path: string, from: string): void => void sites.push({ path, from });
  for (const node of spec.nodes) {
    if ((isTaskNode(node) || isDecisionNode(node)) && node.input) {
      for (const [key, b] of Object.entries(node.input))
        push(`nodes[id=${node.id}].input.${key}`, b.from);
    }
    if (isTaskNode(node) && node.contextWrites) {
      for (const [key, b] of Object.entries(node.contextWrites))
        push(`nodes[id=${node.id}].contextWrites.${key}`, b.from);
    }
    if (isTerminateNode(node) && node.result) push(`nodes[id=${node.id}].result`, node.result.from);
  }
  if (spec.outputs) {
    for (const [key, b] of Object.entries(spec.outputs)) push(`outputs.${key}`, b.from);
  }
  return sites;
}

function sem12BindingNodeRefs(
  spec: WorkflowSpec,
  nodeIds: Set<string>,
  errors: ValidationError[],
): void {
  for (const { path, from } of eachBinding(spec)) {
    if (!from.startsWith("$node.")) continue;
    const referenced = from.slice("$node.".length).split(".", 1)[0]!;
    if (!nodeIds.has(referenced)) {
      errors.push({
        code: "BINDING_UNKNOWN_NODE",
        path,
        message: `binding references unknown node '${referenced}'`,
      });
    }
  }
}

function sem13Backoff(spec: WorkflowSpec, errors: ValidationError[]): void {
  const sites: Array<{ site: string; bundle: PolicyBundle | undefined }> = [
    { site: "policies", bundle: spec.policies },
  ];
  for (const [alias, decl] of Object.entries(spec.operations)) {
    sites.push({ site: `operations[${alias}].defaultPolicies`, bundle: decl.defaultPolicies });
  }
  for (const node of spec.nodes) {
    if (isTaskNode(node) || isDecisionNode(node)) {
      sites.push({ site: `nodes[id=${node.id}].policies`, bundle: node.policies });
    }
  }
  for (const { site, bundle } of sites) {
    const backoff = bundle?.retry?.backoff;
    if (!backoff) continue;
    for (const message of backoffViolations(backoff)) {
      errors.push({ code: "INVALID_BACKOFF", path: `${site}.retry.backoff`, message });
    }
  }
}

function backoffViolations(backoff: Backoff): string[] {
  const out: string[] = [];
  if (backoff.strategy === "exponential" && backoff.multiplier === undefined) {
    out.push("exponential backoff requires a multiplier");
  }
  if (backoff.maxMillis !== undefined && backoff.initialMillis > backoff.maxMillis) {
    out.push(`initialMillis (${backoff.initialMillis}) exceeds maxMillis (${backoff.maxMillis})`);
  }
  return out;
}
