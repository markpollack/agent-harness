package io.github.markpollack.workflow.journal;

import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.workflow.patterns.graph.NodeType;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a single step completion in a workflow execution.
 * Implements {@link JournalEvent} so it is stored as a typed event in the journal ledger.
 *
 * <p>Register at startup via {@code Journal.registerEventType("workflow_step", WorkflowStepEvent.class)}
 * so the JSON storage can deserialize it back to this type.
 *
 * @param timestamp     when this step completed
 * @param workflowRunId the workflow run identifier
 * @param workflowName  the workflow name
 * @param stepName      the node that just executed
 * @param fromStep      the preceding node (null for the first step)
 * @param nodeType      DETERMINISTIC or AGENT
 * @param stepDuration  wall-clock time the step took
 * @param tokensUsed    tokens consumed (0 if not tracked at step level)
 * @param costUsd       estimated USD cost (0.0 if not tracked at step level)
 * @param routingLabel  branch/gate label, or null for sequential steps
 */
public record WorkflowStepEvent(
        Instant timestamp,
        String workflowRunId,
        String workflowName,
        String stepName,
        String fromStep,
        NodeType nodeType,
        Duration stepDuration,
        long tokensUsed,
        double costUsd,
        String routingLabel
) implements JournalEvent {

    @Override
    public String type() {
        return "workflow_step";
    }

    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type());
        map.put("timestamp", timestamp.toString());
        map.put("workflowRunId", workflowRunId);
        map.put("workflowName", workflowName);
        map.put("stepName", stepName);
        if (fromStep != null) {
            map.put("fromStep", fromStep);
        }
        map.put("nodeType", nodeType.name());
        map.put("stepDurationMs", stepDuration.toMillis());
        map.put("tokensUsed", tokensUsed);
        map.put("costUsd", costUsd);
        if (routingLabel != null) {
            map.put("routingLabel", routingLabel);
        }
        return map;
    }
}
