package io.github.markpollack.workflow.engine.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-node durable progress (the second checkpoint entity, D6), keyed
 * {@code (workflowRunId, nodeId)} — never operationRef or attemptNumber (metadata).
 * One row per node, moving through {@code dispatched → retry_scheduled →
 * succeeded|failed}: the pre-terminal states are the dispatch records behind resume's
 * {@code INTERRUPTED} normalization; {@code externalRef} is the optional recovery
 * handle for session-ful operations. Payloads are single JSON TEXT columns (no
 * size-split, no object graphs — the Spring Batch ExecutionContext trap avoided);
 * {@code lastEventSequence} on the terminal row is the atomicity proof that the
 * node's events committed with it.
 */
@Entity
@Table(name = "workflow_node_checkpoint")
@IdClass(WorkflowNodeCheckpointEntity.Key.class)
public class WorkflowNodeCheckpointEntity {

    @Id
    @Column(name = "workflow_run_id")
    private String workflowRunId;

    @Id
    @Column(name = "node_id")
    private String nodeId;

    /** {@code dispatched} | {@code retry_scheduled} | {@code succeeded} | {@code failed}. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "operation_ref", nullable = false)
    private String operationRef;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "retry_delay_millis")
    private Long retryDelayMillis;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "context_writes_json", columnDefinition = "TEXT")
    private String contextWritesJson;

    @Column(name = "last_event_sequence", nullable = false)
    private long lastEventSequence;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowNodeCheckpointEntity() {
    }

    WorkflowNodeCheckpointEntity(String workflowRunId, String nodeId) {
        this.workflowRunId = workflowRunId;
        this.nodeId = nodeId;
    }

    String nodeId() {
        return nodeId;
    }

    String status() {
        return status;
    }

    void status(String status) {
        this.status = status;
    }

    String operationRef() {
        return operationRef;
    }

    void operationRef(String operationRef) {
        this.operationRef = operationRef;
    }

    int attemptNumber() {
        return attemptNumber;
    }

    void attemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    Long retryDelayMillis() {
        return retryDelayMillis;
    }

    void retryDelayMillis(Long retryDelayMillis) {
        this.retryDelayMillis = retryDelayMillis;
    }

    Instant scheduledFor() {
        return scheduledFor;
    }

    void scheduledFor(Instant scheduledFor) {
        this.scheduledFor = scheduledFor;
    }

    String externalRef() {
        return externalRef;
    }

    void externalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    String resultJson() {
        return resultJson;
    }

    void resultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    String contextWritesJson() {
        return contextWritesJson;
    }

    void contextWritesJson(String contextWritesJson) {
        this.contextWritesJson = contextWritesJson;
    }

    long lastEventSequence() {
        return lastEventSequence;
    }

    void lastEventSequence(long lastEventSequence) {
        this.lastEventSequence = lastEventSequence;
    }

    /** Composite key: workflow position identity (DD-7). */
    public static class Key implements Serializable {

        private String workflowRunId;
        private String nodeId;

        public Key() {
        }

        public Key(String workflowRunId, String nodeId) {
            this.workflowRunId = workflowRunId;
            this.nodeId = nodeId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key key
                    && Objects.equals(workflowRunId, key.workflowRunId)
                    && Objects.equals(nodeId, key.nodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workflowRunId, nodeId);
        }
    }
}
