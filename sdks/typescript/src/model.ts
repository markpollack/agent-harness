/**
 * The v2-alpha WorkflowSpec model: `readonly` types mirroring the wire format 1:1.
 *
 * The JSON wire format is the Published Language; these types are the TypeScript
 * conformist rendering (DD-9/DD-10). Because the wire is already camelCase and uses
 * `from`/`kind` as ordinary keys, the model **is** the wire shape with types — no
 * snake_case translation (unlike Python). Consequences:
 *
 * - Absent optional field = the key is omitted (`exactOptionalPropertyTypes`), which
 *   is exactly the no-nulls rule (canonical rule 2). A *present-but-empty* section
 *   (`types: {}`) is a distinct, preserved state (canonical rule 4).
 * - `canonicalize()` drops omitted keys and keeps `{}` — so `toJson(spec)` is
 *   `canonicalize(spec)` with no wire-translation layer (verified against the corpus).
 * - Nodes and edge conditions are discriminated unions on `kind` — the sum-type
 *   rendering of "invalid states unrepresentable" (DDD KB). Construction-time invariant
 *   enforcement lives in the binding helpers (builder) and the validator (two-phase,
 *   DD-13), not in the plain data types.
 *
 * Open sections (`types`, `constants`, `contextSchema`, embedded schemas) are carried
 * verbatim as `unknown` JSON values and round-trip losslessly.
 */

export const API_VERSION = "workflow/v2alpha";
export const KIND = "Workflow";

/** Node-id charset (no dots — the §12 binding grammar depends on it). */
export const NODE_ID_PATTERN = /^[A-Za-z0-9_-]+$/;

/** The frozen §12 binding path grammar (byte-identical to the schema `binding.from` pattern). */
export const BINDING_PATTERN =
  /^\$(input(\.[A-Za-z0-9_-]+)?|context\.[\x21-\x7E]+|const\.[\x21-\x7E]+|node\.[A-Za-z0-9_-]+\.(output|decision))$/;

export const MAX_MILLIS = 9007199254740991; // 2^53 - 1
export const MAX_ATTEMPTS = 2147483647; // 2^31 - 1

export type TerminateStatus = "completed" | "failed" | "cancelled" | "aborted";
export type BackoffStrategy = "fixed" | "exponential";
export type ExecutionMode = "request-response";

// ---------------------------------------------------------------------------
// Bindings (§12)
// ---------------------------------------------------------------------------

export interface Binding {
  readonly from: string;
}

// ---------------------------------------------------------------------------
// Policies (§4)
// ---------------------------------------------------------------------------

export interface Backoff {
  readonly strategy: BackoffStrategy;
  readonly initialMillis: number;
  readonly maxMillis?: number;
  readonly multiplier?: number;
}

export interface ErrorMatcher {
  readonly code: string;
}

export interface RetryPolicy {
  /** Counts the FIRST attempt: 3 means three total, not three retries. */
  readonly maxAttempts: number;
  readonly backoff?: Backoff;
  readonly retryOn?: readonly ErrorMatcher[];
}

export interface Timeout {
  readonly perAttemptMillis: number;
}

export interface PolicyBundle {
  readonly retry?: RetryPolicy;
  readonly timeout?: Timeout;
}

// ---------------------------------------------------------------------------
// Operations
// ---------------------------------------------------------------------------

export interface Execution {
  readonly mode: ExecutionMode;
}

export interface OperationDeclaration {
  readonly ref: string;
  readonly inputSchemaRef?: string;
  readonly inputSchema?: unknown;
  readonly outputSchema?: unknown;
  readonly execution?: Execution;
  readonly defaultPolicies?: PolicyBundle;
}

// ---------------------------------------------------------------------------
// Nodes (closed task | decision | terminate algebra, discriminated on `kind`)
// ---------------------------------------------------------------------------

interface BaseNode {
  readonly id: string;
  readonly name?: string;
  readonly description?: string;
  readonly annotations?: Readonly<Record<string, string>>;
}

export interface TaskNode extends BaseNode {
  readonly kind: "task";
  readonly operation: string;
  readonly input?: Readonly<Record<string, Binding>>;
  readonly contextWrites?: Readonly<Record<string, Binding>>;
  readonly policies?: PolicyBundle;
}

export interface DecisionNode extends BaseNode {
  readonly kind: "decision";
  readonly operation: string;
  readonly outcomes: readonly string[];
  readonly input?: Readonly<Record<string, Binding>>;
  readonly policies?: PolicyBundle;
}

export interface TerminateNode extends BaseNode {
  readonly kind: "terminate";
  readonly status: TerminateStatus;
  readonly result?: Binding;
}

export type Node = TaskNode | DecisionNode | TerminateNode;

export const isTaskNode = (node: Node): node is TaskNode => node.kind === "task";
export const isDecisionNode = (node: Node): node is DecisionNode => node.kind === "decision";
export const isTerminateNode = (node: Node): node is TerminateNode => node.kind === "terminate";

// ---------------------------------------------------------------------------
// Edges (closed always | decisionResult | error condition algebra)
// ---------------------------------------------------------------------------

export interface Always {
  readonly kind: "always";
}

export interface DecisionResult {
  readonly kind: "decisionResult";
  readonly value: string;
}

/** Error-envelope matcher: at least one of `code`/`retryable` (schema minProperties 1). */
export interface ErrorMatch {
  readonly code?: string;
  readonly retryable?: boolean;
}

export interface ErrorCondition {
  readonly kind: "error";
  readonly match: ErrorMatch;
}

export type EdgeCondition = Always | DecisionResult | ErrorCondition;

export interface Edge {
  readonly from: string;
  readonly to: string;
  readonly when: EdgeCondition;
  readonly label?: string;
}

// ---------------------------------------------------------------------------
// Metadata + the spec root
// ---------------------------------------------------------------------------

export interface Metadata {
  readonly name: string;
  readonly version?: string;
  readonly labels?: Readonly<Record<string, string>>;
  readonly annotations?: Readonly<Record<string, string>>;
}

export interface WorkflowSpec {
  readonly apiVersion: string;
  readonly kind: string;
  readonly metadata: Metadata;
  readonly types?: Readonly<Record<string, unknown>>;
  readonly constants?: Readonly<Record<string, unknown>>;
  readonly contextSchema?: Readonly<Record<string, unknown>>;
  readonly operations: Readonly<Record<string, OperationDeclaration>>;
  readonly nodes: readonly Node[];
  readonly edges: readonly Edge[];
  readonly policies?: PolicyBundle;
  readonly entrypoint: string;
  readonly outputs?: Readonly<Record<string, Binding>>;
}
