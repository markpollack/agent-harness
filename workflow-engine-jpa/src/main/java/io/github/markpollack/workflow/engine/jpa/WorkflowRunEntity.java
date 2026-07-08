package io.github.markpollack.workflow.engine.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Run-level durable state (one of the two checkpoint entities, D6): identity pinning
 * ({@code workflowSpecRef} + {@code canonicalSpecHash}), terminal status, and
 * {@code lastEventSequence} — the committed-event-boundary proof (0 = nothing
 * committed yet, the ratified sentinel).
 *
 * <p>{@code claimedBy}/{@code lastHeartbeat}/{@code version} are the claim-shaped
 * columns (D1): single-runner-per-run is a documented alpha constraint, not enforced —
 * nothing writes claims yet; heartbeat-CAS enforcement can arrive without migration.
 * {@code @Version} is the optimistic commit guard: every boundary mutates
 * {@code lastEventSequence}, so concurrent runners collide here, never last-wins.
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRunEntity {

    @Id
    @Column(name = "workflow_run_id")
    private String workflowRunId;

    @Column(name = "workflow_spec_ref", nullable = false)
    private String workflowSpecRef;

    @Column(name = "canonical_spec_hash", nullable = false, length = 64)
    private String canonicalSpecHash;

    /** {@code running} | {@code completed} | {@code failed} | {@code cancelled} | {@code aborted}. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "running";

    @Column(name = "last_event_sequence", nullable = false)
    private long lastEventSequence;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowRunEntity() {
    }

    WorkflowRunEntity(String workflowRunId, String workflowSpecRef, String canonicalSpecHash) {
        this.workflowRunId = workflowRunId;
        this.workflowSpecRef = workflowSpecRef;
        this.canonicalSpecHash = canonicalSpecHash;
    }

    String workflowRunId() {
        return workflowRunId;
    }

    String workflowSpecRef() {
        return workflowSpecRef;
    }

    String canonicalSpecHash() {
        return canonicalSpecHash;
    }

    String status() {
        return status;
    }

    void status(String status) {
        this.status = status;
    }

    long lastEventSequence() {
        return lastEventSequence;
    }

    void lastEventSequence(long lastEventSequence) {
        this.lastEventSequence = lastEventSequence;
    }

    long version() {
        return version;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
