package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The wire projection of the {@link WorkflowEvent} envelope
 * ({@code spec/events/workflow-event.schema.json} is the normative shape, frozen at
 * Step 2.5): CamelCase event-type names, RFC 3339 timestamp, no nulls on the wire.
 * The Stage-3 durable journal persists this form; the reading direction arrives with
 * the journal.
 */
public final class WorkflowEventCodec {

    private WorkflowEventCodec() {
    }

    public static ObjectNode toJson(WorkflowEvent event) {
        ObjectNode node = WorkflowEventJson.mapper().createObjectNode();
        node.put("eventType", event.eventType().wireName());
        node.put("workflowRunId", event.workflowRunId());
        node.put("workflowSpecRef", event.workflowSpecRef());
        node.put("sequence", event.sequence());
        node.put("timestamp", event.timestamp().toString());
        if (event.nodeId() != null) {
            node.put("nodeId", event.nodeId());
        }
        if (event.operationRef() != null) {
            node.put("operationRef", event.operationRef());
        }
        if (event.attemptNumber() != null) {
            node.put("attemptNumber", event.attemptNumber());
        }
        if (event.payload() != null) {
            node.set("payload", WorkflowEventJson.mapper().valueToTree(event.payload()));
        }
        if (event.attributes() != null) {
            node.set("attributes", WorkflowEventJson.mapper().valueToTree(event.attributes()));
        }
        return node;
    }
}
