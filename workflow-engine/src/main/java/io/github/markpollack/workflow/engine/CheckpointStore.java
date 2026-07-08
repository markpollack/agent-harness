package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The durability plane (DD-16): commits interpreter progress at the §10 boundaries and
 * replays it on resume. The interpreter — never a handler — drives every method; one
 * store call = one atomic commit boundary (events + state together, the review-21
 * atomicity rule). Implementations enforce the committed-event rule with optimistic
 * versioning: a conflicting concurrent commit throws {@link CheckpointConflictException},
 * never silently overwrites.
 *
 * <p>Node progress is keyed {@code (workflowRunId, nodeId)} — never operationRef or
 * attemptNumber (those are metadata) — and moves through
 * {@code dispatched → retry_scheduled → succeeded|failed}. The pre-terminal states are
 * the dispatch records that make resume's {@code INTERRUPTED} normalization and
 * stale-retry handling possible; the optional {@code externalRef} slot on them is the
 * recovery handle for session-ful operations (DESIGN § Operation Contract).
 *
 * <p>Single runner per run is a documented alpha constraint, not enforced (D1): the
 * run row keeps claim-shaped columns ({@code claimedBy}, {@code lastHeartbeat},
 * {@code version}) so heartbeat-CAS enforcement can arrive without migration; nothing
 * writes claims in alpha. Retention policy likewise lives above this SPI — the store
 * only exposes {@link #prune}.
 */
public interface CheckpointStore {

    /**
     * Opens a new run or prepares a resume. {@code workflowRunId} is caller-minted,
     * never derived. On resume, the supplied spec is pinned to the run identity:
     * a {@code canonicalSpecHash} mismatch rejects the resume — a run can never
     * silently continue under a changed definition (review-21). Terminal runs always
     * refuse resume; there is no {@code restartable=false} knob.
     *
     * @throws ResumeRejectedException on terminal state or canonical-hash mismatch
     */
    RunState openRun(String workflowRunId, String workflowSpecRef, String canonicalSpecHash);

    /** Commits a dispatch boundary: the attempt record + all events up to its OperationDispatched. */
    void commitDispatch(String workflowRunId, DispatchRecord dispatch, List<WorkflowEvent> events);

    /** Commits a retry boundary: the scheduled-retry record + events through RetryScheduled. */
    void commitRetry(String workflowRunId, RetryRecord retry, List<WorkflowEvent> events);

    /** Commits node completion: the checkpoint + events through EdgeSelected, one transaction. */
    void commitNode(String workflowRunId, NodeCheckpoint checkpoint, List<WorkflowEvent> events);

    /** Commits the terminal boundary: run status + the remaining events ending in the terminal event. */
    void completeRun(String workflowRunId, String terminalState, List<WorkflowEvent> events);

    /**
     * The committed journal: {@code WorkflowEventCodec} wire envelopes in sequence
     * order — the durable record of the run (§10); observer sinks are non-authoritative.
     */
    List<ObjectNode> journal(String workflowRunId);

    /** Deletes runs (and their checkpoints/journal) older than the cutoff; policy lives upstream. */
    PruneResult prune(Instant olderThan, boolean terminalOnly);

    /**
     * Everything resume needs: committed nodes in commit order (their events are
     * committed with them, so silent replay is sound), at most one in-flight attempt
     * (alpha execution is sequential), and the committed event boundary —
     * {@code lastEventSequence} 0 means "row exists, nothing committed yet" (the
     * ratified 0-sentinel): resume from 0 behaves as a fresh start.
     */
    record RunState(
            boolean resumed,
            long lastEventSequence,
            List<NodeCheckpoint> committedNodes,
            Optional<InFlightAttempt> inFlight) {

        public RunState {
            committedNodes = List.copyOf(committedNodes);
        }
    }

    /** A committed attempt boundary awaiting its result when the run is reopened. */
    record InFlightAttempt(
            String nodeId,
            String operationRef,
            int attemptNumber,
            boolean retryScheduled,
            Long retryDelayMillis,
            Instant scheduledFor,
            String externalRef) {
    }

    /** One dispatched attempt (state {@code dispatched}); {@code externalRef} is the recovery slot. */
    record DispatchRecord(
            String nodeId,
            String operationRef,
            int attemptNumber,
            Instant scheduledFor,
            String externalRef) {
    }

    /** A granted retry (state {@code retry_scheduled}); on resume a stale one fires immediately. */
    record RetryRecord(
            String nodeId,
            String operationRef,
            int attemptNumber,
            long retryDelayMillis,
            Instant scheduledFor) {
    }

    /**
     * A completed node: the recorded {@link OperationResult} is replayed into bindings
     * and routing on resume without re-dispatch — the precise crash-recovery promise.
     * {@code state} is the NodeCompleted vocabulary subset reachable here
     * ({@code succeeded} | {@code failed}); {@code contextWrites} are the applied §14
     * writes (persisted, not recomputed, so resume needs no re-evaluation).
     */
    record NodeCheckpoint(
            String nodeId,
            String state,
            OperationResult result,
            Map<String, Object> contextWrites,
            int attempts) {

        public NodeCheckpoint {
            contextWrites = contextWrites == null ? Map.of() : contextWrites;
        }
    }

    record PruneResult(int runsPruned) {
    }
}
