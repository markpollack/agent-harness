package io.github.markpollack.workflow.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deterministic conformance projection of an event stream (formalized at the Step
 * 2.5 freeze; shape here per review-21): {@code sequence}, {@code eventType} (wire
 * name), the node/operation/attempt envelope fields when present, and the payload —
 * excluding {@code timestamp} and {@code attributes}, and excluding the run-scoped
 * identity fields ({@code workflowRunId}, {@code workflowSpecRef}) which are constant
 * per stream and carried by the fixture document instead.
 */
final class EventStreamProjection {

    private EventStreamProjection() {
    }

    static List<Map<String, Object>> project(List<WorkflowEvent> events) {
        List<Map<String, Object>> projection = new ArrayList<>();
        for (WorkflowEvent event : events) {
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("sequence", event.sequence());
            projected.put("eventType", event.eventType().wireName());
            if (event.nodeId() != null) {
                projected.put("nodeId", event.nodeId());
            }
            if (event.operationRef() != null) {
                projected.put("operationRef", event.operationRef());
            }
            if (event.attemptNumber() != null) {
                projected.put("attemptNumber", event.attemptNumber());
            }
            if (event.payload() != null) {
                projected.put("payload", event.payload());
            }
            projection.add(projected);
        }
        return projection;
    }
}
