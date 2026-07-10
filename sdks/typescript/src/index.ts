/**
 * TypeScript authoring SDK for the agent-workflow Workflow IR (`workflow/v2alpha`).
 *
 * Authors, validates, and emits `WorkflowSpec` JSON executed by the JVM engine.
 * The SDK is an emitter, never an engine: nothing here executes workflows.
 *
 * The public API is exactly this module's exports; the package `exports` map is the
 * fence around everything else.
 */

export const VERSION = "0.1.0-alpha.1";

export { canonicalString } from "./canonical.js";
export { type ValidationError, WorkflowValidationError } from "./errors.js";
export { load, toJson } from "./io.js";
export {
  API_VERSION,
  BINDING_PATTERN,
  isDecisionNode,
  isTaskNode,
  isTerminateNode,
  KIND,
  NODE_ID_PATTERN,
  type Always,
  type Backoff,
  type BackoffStrategy,
  type Binding,
  type DecisionNode,
  type DecisionResult,
  type Edge,
  type EdgeCondition,
  type ErrorCondition,
  type ErrorMatch,
  type ErrorMatcher,
  type Execution,
  type ExecutionMode,
  type Metadata,
  type Node,
  type OperationDeclaration,
  type PolicyBundle,
  type RetryPolicy,
  type TaskNode,
  type TerminateNode,
  type TerminateStatus,
  type Timeout,
  type WorkflowSpec,
} from "./model.js";
export { DuplicateKeyError, parseStrict } from "./parse.js";
export { validateParsed } from "./validation.js";
