package io.github.markpollack.workflow.engine;

/**
 * The canonical event types (alpha spec §9, frozen at Step 2.5): the twelve required
 * types plus the terminal {@code WorkflowCancelled}/{@code WorkflowAborted} (every
 * §9 terminal workflow state has exactly one terminal event type). Retry exhaustion is
 * expressed through routing + terminal semantics, not a dedicated event type. A
 * run-level {@code paused} state is reserved, deliberately without an event type yet.
 *
 * <p><b>Wire names are NOT these constant names.</b> The §9 wire forms are CamelCase
 * ({@code WorkflowStarted}, …) — use {@link #wireName()}; never serialize this enum
 * with default naming ({@code spec/events/workflow-event.schema.json} is normative).
 */
public enum WorkflowEventType {
    WORKFLOW_STARTED,
    NODE_STARTED,
    OPERATION_DISPATCHED,
    OPERATION_SUCCEEDED,
    OPERATION_FAILED,
    RETRY_SCHEDULED,
    BINDING_EVALUATED,
    CONTEXT_WRITE_APPLIED,
    EDGE_SELECTED,
    NODE_COMPLETED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,
    WORKFLOW_CANCELLED,
    WORKFLOW_ABORTED;

    /** The §9 CamelCase wire form ({@code WorkflowStarted}, {@code EdgeSelected}, …). */
    public String wireName() {
        StringBuilder sb = new StringBuilder();
        for (String part : name().split("_")) {
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }
}
