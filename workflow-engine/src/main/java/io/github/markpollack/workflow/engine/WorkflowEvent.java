package io.github.markpollack.workflow.engine;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical event envelope (alpha spec §8). Events are the evidence ledger: emitted in
 * deterministic execution order with a monotonically increasing {@code sequence} per
 * {@code workflowRunId}.
 *
 * <p>{@code timestamp} and {@code attributes} are excluded from the deterministic
 * conformance projection (golden-stream comparison); {@code attributes} is the DD-19
 * namespaced extension bag — ignorable, never control-flow-relevant.
 *
 * <p>{@code payload} carries the per-type required fields (§9); typed factory methods
 * arrive with the event sink at Step 2.2, and the wire projection
 * ({@code spec/events/workflow-event.schema.json}) freezes at Step 2.5.
 */
public record WorkflowEvent(
        WorkflowEventType eventType,
        String workflowRunId,
        String workflowSpecRef,
        long sequence,
        Instant timestamp,
        String nodeId,
        String operationRef,
        Integer attemptNumber,
        Map<String, Object> payload,
        Map<String, Object> attributes) {

    public WorkflowEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(workflowSpecRef, "workflowSpecRef");
        Objects.requireNonNull(timestamp, "timestamp");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0: " + sequence);
        }
        payload = payload == null ? null : Map.copyOf(payload);
        attributes = attributes == null ? null : Map.copyOf(attributes);
    }
}
