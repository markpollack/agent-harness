package io.github.markpollack.workflow.engine;

/**
 * The required canonical event types (alpha spec §9). {@code WorkflowCancelled} /
 * {@code WorkflowAborted} are added at the Step 2.5 event-contract freeze (queued);
 * retry exhaustion is expressed through routing + terminal semantics, not a dedicated
 * event type.
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
    WORKFLOW_FAILED
}
