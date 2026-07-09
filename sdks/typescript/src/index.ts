/**
 * TypeScript authoring SDK for the agent-workflow Workflow IR (`workflow/v2alpha`).
 *
 * Authors, validates, and emits `WorkflowSpec` JSON executed by the JVM engine.
 * The SDK is an emitter, never an engine: nothing here executes workflows.
 *
 * The public API is exactly this module's exports; everything else is implementation
 * detail (the package `exports` map is the fence).
 */

export const VERSION = "0.1.0-alpha.1";
