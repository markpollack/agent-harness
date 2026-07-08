package io.github.markpollack.workflow.engine.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.workflow.engine.CheckpointConflictException;
import io.github.markpollack.workflow.engine.CheckpointStore;
import io.github.markpollack.workflow.engine.OperationResult;
import io.github.markpollack.workflow.engine.OperationResultCodec;
import io.github.markpollack.workflow.engine.ResumeRejectedException;
import io.github.markpollack.workflow.engine.WorkflowEvent;
import io.github.markpollack.workflow.engine.WorkflowEventCodec;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import org.springframework.transaction.annotation.Transactional;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * JPA-backed {@link CheckpointStore}: two checkpoint entities ({@code workflow_run},
 * {@code workflow_node_checkpoint}) plus the {@code workflow_event} journal, per the
 * scheduler study's db-scheduler/JobRunr shape — JSON TEXT payloads, optimistic
 * {@code @Version} commits, no locks held across attempts.
 *
 * <p>Every {@code commit*} method is one {@code @Transactional} boundary: control-flow
 * events and the checkpoint state they advance commit together or not at all
 * (review-21 atomicity rule); {@code lastEventSequence} on the run row — updated in the
 * same transaction — is the committed-boundary proof, and the contiguity check against
 * it turns duplicate or gapped commits into {@link CheckpointConflictException}
 * (the store-level §10 committed-event rule).
 *
 * <p>Wire framing: node results round-trip through {@code OperationResultCodec}
 * (resumed outputs are JSON-natural Java values — what a Python worker would see);
 * journal rows carry full {@code WorkflowEventCodec} envelopes.
 */
@Transactional
public class JpaCheckpointStore implements CheckpointStore {

    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled", "aborted");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final EntityManager em;
    private final ObjectMapper mapper = new ObjectMapper();

    public JpaCheckpointStore(EntityManager em) {
        this.em = Objects.requireNonNull(em, "em");
    }

    @Override
    public RunState openRun(String workflowRunId, String workflowSpecRef, String canonicalSpecHash) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(workflowSpecRef, "workflowSpecRef");
        Objects.requireNonNull(canonicalSpecHash, "canonicalSpecHash");
        WorkflowRunEntity run = em.find(WorkflowRunEntity.class, workflowRunId);
        if (run == null) {
            em.persist(new WorkflowRunEntity(workflowRunId, workflowSpecRef, canonicalSpecHash));
            try {
                flush();
            } catch (PersistenceException ex) {
                // two runners racing the first open: the loser is a conflict, not a crash
                throw new CheckpointConflictException(
                        "concurrent openRun for '" + workflowRunId + "'", ex);
            }
            return new RunState(false, 0, List.of(), Optional.empty());
        }
        if (TERMINAL.contains(run.status())) {
            throw new ResumeRejectedException("run '" + workflowRunId + "' is terminal ("
                    + run.status() + ") and refuses resume");
        }
        if (!run.canonicalSpecHash().equals(canonicalSpecHash)) {
            throw new ResumeRejectedException("canonical spec hash mismatch for run '" + workflowRunId
                    + "': a resumed run cannot continue under a changed definition");
        }
        List<WorkflowNodeCheckpointEntity> rows = em.createQuery("""
                        SELECT n FROM WorkflowNodeCheckpointEntity n
                        WHERE n.workflowRunId = :runId
                        ORDER BY n.lastEventSequence, n.nodeId
                        """, WorkflowNodeCheckpointEntity.class)
                .setParameter("runId", workflowRunId)
                .getResultList();
        List<NodeCheckpoint> committed = new ArrayList<>();
        InFlightAttempt inFlight = null;
        for (WorkflowNodeCheckpointEntity row : rows) {
            if (row.status().equals("succeeded") || row.status().equals("failed")) {
                committed.add(toCheckpoint(row));
            } else {
                if (inFlight != null) {
                    throw new IllegalStateException("run '" + workflowRunId + "' has multiple in-flight "
                            + "nodes — corrupt state (alpha execution is sequential)");
                }
                inFlight = new InFlightAttempt(row.nodeId(), row.operationRef(), row.attemptNumber(),
                        row.status().equals("retry_scheduled"), row.retryDelayMillis(),
                        row.scheduledFor(), row.externalRef());
            }
        }
        return new RunState(true, run.lastEventSequence(), committed, Optional.ofNullable(inFlight));
    }

    @Override
    public void commitDispatch(String workflowRunId, DispatchRecord dispatch, List<WorkflowEvent> events) {
        WorkflowRunEntity run = liveRun(workflowRunId);
        appendEvents(run, events);
        WorkflowNodeCheckpointEntity row = nodeRow(workflowRunId, dispatch.nodeId());
        row.status("dispatched");
        row.operationRef(dispatch.operationRef());
        row.attemptNumber(dispatch.attemptNumber());
        row.retryDelayMillis(null);
        row.scheduledFor(dispatch.scheduledFor());
        if (dispatch.externalRef() != null) {
            // never null out an existing handle: the recovery slot survives attempt
            // increments (DESIGN § Operation Contract)
            row.externalRef(dispatch.externalRef());
        }
        row.lastEventSequence(run.lastEventSequence());
        persistIfNew(row);
        flush();
    }

    @Override
    public void commitRetry(String workflowRunId, RetryRecord retry, List<WorkflowEvent> events) {
        WorkflowRunEntity run = liveRun(workflowRunId);
        appendEvents(run, events);
        WorkflowNodeCheckpointEntity row = nodeRow(workflowRunId, retry.nodeId());
        row.status("retry_scheduled");
        row.operationRef(retry.operationRef());
        row.attemptNumber(retry.attemptNumber());
        row.retryDelayMillis(retry.retryDelayMillis());
        row.scheduledFor(retry.scheduledFor());
        row.lastEventSequence(run.lastEventSequence());
        persistIfNew(row);
        flush();
    }

    @Override
    public void commitNode(String workflowRunId, NodeCheckpoint checkpoint, List<WorkflowEvent> events) {
        WorkflowRunEntity run = liveRun(workflowRunId);
        appendEvents(run, events);
        WorkflowNodeCheckpointEntity row = nodeRow(workflowRunId, checkpoint.nodeId());
        row.status(checkpoint.state());
        if (row.operationRef() == null) {
            // an INTERRUPTED normalization can complete a node without a fresh dispatch row
            row.operationRef("(none)");
        }
        row.attemptNumber(checkpoint.attempts());
        row.retryDelayMillis(null);
        row.resultJson(OperationResultCodec.toJson(checkpoint.result()).toString());
        row.contextWritesJson(writeContextWrites(checkpoint.contextWrites()));
        row.lastEventSequence(run.lastEventSequence());
        persistIfNew(row);
        flush();
    }

    @Override
    public void completeRun(String workflowRunId, String terminalState, List<WorkflowEvent> events) {
        WorkflowRunEntity run = liveRun(workflowRunId);
        appendEvents(run, events);
        run.status(Objects.requireNonNull(terminalState, "terminalState"));
        flush();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ObjectNode> journal(String workflowRunId) {
        return em.createQuery("""
                        SELECT e FROM WorkflowEventEntity e
                        WHERE e.workflowRunId = :runId
                        ORDER BY e.sequenceNumber
                        """, WorkflowEventEntity.class)
                .setParameter("runId", workflowRunId)
                .getResultList().stream()
                .map(row -> readEnvelope(row.eventJson()))
                .toList();
    }

    @Override
    public PruneResult prune(Instant olderThan, boolean terminalOnly) {
        Objects.requireNonNull(olderThan, "olderThan");
        String query = terminalOnly
                ? "SELECT r.workflowRunId FROM WorkflowRunEntity r"
                        + " WHERE r.updatedAt < :cutoff AND r.status <> 'running'"
                : "SELECT r.workflowRunId FROM WorkflowRunEntity r WHERE r.updatedAt < :cutoff";
        List<String> runIds = em.createQuery(query, String.class)
                .setParameter("cutoff", olderThan)
                .getResultList();
        if (runIds.isEmpty()) {
            return new PruneResult(0);
        }
        em.createQuery("DELETE FROM WorkflowEventEntity e WHERE e.workflowRunId IN :ids")
                .setParameter("ids", runIds).executeUpdate();
        em.createQuery("DELETE FROM WorkflowNodeCheckpointEntity n WHERE n.workflowRunId IN :ids")
                .setParameter("ids", runIds).executeUpdate();
        em.createQuery("DELETE FROM WorkflowRunEntity r WHERE r.workflowRunId IN :ids")
                .setParameter("ids", runIds).executeUpdate();
        return new PruneResult(runIds.size());
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private WorkflowRunEntity liveRun(String workflowRunId) {
        WorkflowRunEntity run = em.find(WorkflowRunEntity.class, workflowRunId);
        if (run == null) {
            throw new CheckpointConflictException("unknown run: " + workflowRunId);
        }
        if (TERMINAL.contains(run.status())) {
            throw new CheckpointConflictException("run '" + workflowRunId + "' is already terminal");
        }
        return run;
    }

    private void appendEvents(WorkflowRunEntity run, List<WorkflowEvent> events) {
        long expected = run.lastEventSequence() + 1;
        for (WorkflowEvent event : events) {
            if (event.sequence() != expected) {
                throw new CheckpointConflictException("event sequence gap for run '"
                        + run.workflowRunId() + "': expected " + expected + " but got "
                        + event.sequence() + " (§10 committed-event rule)");
            }
            em.persist(new WorkflowEventEntity(run.workflowRunId(), event.sequence(),
                    event.eventType().wireName(), event.nodeId(),
                    WorkflowEventCodec.toJson(event).toString()));
            run.lastEventSequence(expected);
            expected++;
        }
    }

    /** Existing row or a transient one — callers populate it, then {@link #persistIfNew}. */
    private WorkflowNodeCheckpointEntity nodeRow(String workflowRunId, String nodeId) {
        WorkflowNodeCheckpointEntity row = em.find(WorkflowNodeCheckpointEntity.class,
                new WorkflowNodeCheckpointEntity.Key(workflowRunId, nodeId));
        return row != null ? row : new WorkflowNodeCheckpointEntity(workflowRunId, nodeId);
    }

    private void persistIfNew(WorkflowNodeCheckpointEntity row) {
        if (!em.contains(row)) {
            em.persist(row);
        }
    }

    /** Flushes inside the transaction so optimistic conflicts surface as our exception type. */
    private void flush() {
        try {
            em.flush();
        } catch (OptimisticLockException ex) {
            throw new CheckpointConflictException("optimistic commit conflict (§10 committed-event "
                    + "rule): another runner advanced this run", ex);
        } catch (PersistenceException ex) {
            if (ex.getCause() instanceof OptimisticLockException) {
                throw new CheckpointConflictException("optimistic commit conflict", ex);
            }
            throw ex;
        }
    }

    private NodeCheckpoint toCheckpoint(WorkflowNodeCheckpointEntity row) {
        OperationResult result = OperationResultCodec.fromJson(readEnvelope(row.resultJson()));
        Map<String, Object> writes = row.contextWritesJson() == null ? Map.of()
                : readContextWrites(row.contextWritesJson());
        return new NodeCheckpoint(row.nodeId(), row.status(), result, writes, row.attemptNumber());
    }

    private String writeContextWrites(Map<String, Object> writes) {
        if (writes == null || writes.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(writes);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException("context writes are not JSON-serializable", ex);
        }
    }

    private Map<String, Object> readContextWrites(String json) {
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException("corrupt context_writes_json payload", ex);
        }
    }

    private ObjectNode readEnvelope(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException("corrupt JSON payload column", ex);
        }
    }
}
