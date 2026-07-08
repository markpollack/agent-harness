package io.github.markpollack.workflow.engine.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * The durable event journal (the 2.2 journal/observer split's journal side): one row
 * per committed event, the full {@code WorkflowEventCodec} wire envelope in a JSON
 * TEXT column, appended inside the same transaction as the checkpoint state it
 * accompanies (review-21 atomicity rule). Append-only — no {@code @Version}; the
 * composite primary key plus the run row's contiguity check make duplicates and gaps
 * commit-time conflicts.
 */
@Entity
@Table(name = "workflow_event")
@IdClass(WorkflowEventEntity.Key.class)
public class WorkflowEventEntity {

    @Id
    @Column(name = "workflow_run_id")
    private String workflowRunId;

    @Id
    @Column(name = "sequence_number")
    private long sequenceNumber;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "node_id")
    private String nodeId;

    @Column(name = "event_json", columnDefinition = "TEXT", nullable = false)
    private String eventJson;

    protected WorkflowEventEntity() {
    }

    WorkflowEventEntity(String workflowRunId, long sequenceNumber, String eventType,
            String nodeId, String eventJson) {
        this.workflowRunId = workflowRunId;
        this.sequenceNumber = sequenceNumber;
        this.eventType = eventType;
        this.nodeId = nodeId;
        this.eventJson = eventJson;
    }

    long sequenceNumber() {
        return sequenceNumber;
    }

    String eventJson() {
        return eventJson;
    }

    /** Composite key: the run's monotonic sequence is the event identity (§10). */
    public static class Key implements Serializable {

        private String workflowRunId;
        private long sequenceNumber;

        public Key() {
        }

        public Key(String workflowRunId, long sequenceNumber) {
            this.workflowRunId = workflowRunId;
            this.sequenceNumber = sequenceNumber;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key key
                    && sequenceNumber == key.sequenceNumber
                    && Objects.equals(workflowRunId, key.workflowRunId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workflowRunId, sequenceNumber);
        }
    }
}
