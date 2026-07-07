package io.github.markpollack.workflow.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** Envelope invariants and the §9 required-event-type catalog. */
class WorkflowEventTest {

    private static final Instant T0 = Instant.parse("2026-05-15T16:00:00Z");

    @Test
    void envelopeCarriesRequiredFields() {
        WorkflowEvent event = new WorkflowEvent(
                WorkflowEventType.EDGE_SELECTED, "run-123", "workflow://registry/pr-review@1.0.0",
                12, T0, "route", null, null,
                Map.of("to", "post_comment"), null);

        assertThat(event.eventType()).isEqualTo(WorkflowEventType.EDGE_SELECTED);
        assertThat(event.workflowRunId()).isEqualTo("run-123");
        assertThat(event.workflowSpecRef()).isEqualTo("workflow://registry/pr-review@1.0.0");
        assertThat(event.sequence()).isEqualTo(12);
        assertThat(event.nodeId()).isEqualTo("route");
        assertThat(event.payload()).containsEntry("to", "post_comment");
    }

    @Test
    void requiredEnvelopeFieldsAreEnforced() {
        assertThatNullPointerException().isThrownBy(() -> new WorkflowEvent(
                null, "r", "s", 0, T0, null, null, null, null, null));
        assertThatNullPointerException().isThrownBy(() -> new WorkflowEvent(
                WorkflowEventType.WORKFLOW_STARTED, null, "s", 0, T0, null, null, null, null, null));
        assertThatNullPointerException().isThrownBy(() -> new WorkflowEvent(
                WorkflowEventType.WORKFLOW_STARTED, "r", null, 0, T0, null, null, null, null, null));
        assertThatNullPointerException().isThrownBy(() -> new WorkflowEvent(
                WorkflowEventType.WORKFLOW_STARTED, "r", "s", 0, null, null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowEvent(
                WorkflowEventType.WORKFLOW_STARTED, "r", "s", -1, T0, null, null, null, null, null));
    }

    @Test
    void payloadAndAttributesAreDefensivelyCopied() {
        Map<String, Object> payload = new HashMap<>(Map.of("k", "v"));
        WorkflowEvent event = new WorkflowEvent(
                WorkflowEventType.NODE_STARTED, "r", "s", 1, T0, "n", null, null, payload, null);

        payload.put("k2", "v2");

        assertThat(event.payload()).containsOnlyKeys("k");
    }

    @Test
    void allTwelveRequiredAlphaEventTypesExist() {
        // Alpha spec §9 required list; WorkflowCancelled/WorkflowAborted arrive at 2.5.
        assertThat(WorkflowEventType.values()).containsExactlyInAnyOrder(
                WorkflowEventType.WORKFLOW_STARTED,
                WorkflowEventType.NODE_STARTED,
                WorkflowEventType.OPERATION_DISPATCHED,
                WorkflowEventType.OPERATION_SUCCEEDED,
                WorkflowEventType.OPERATION_FAILED,
                WorkflowEventType.RETRY_SCHEDULED,
                WorkflowEventType.BINDING_EVALUATED,
                WorkflowEventType.CONTEXT_WRITE_APPLIED,
                WorkflowEventType.EDGE_SELECTED,
                WorkflowEventType.NODE_COMPLETED,
                WorkflowEventType.WORKFLOW_COMPLETED,
                WorkflowEventType.WORKFLOW_FAILED);
    }
}
