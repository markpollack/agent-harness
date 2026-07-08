package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Ephemeral {@link CheckpointStore} for tests and single-process durability semantics
 * (resume within one JVM). Enforces the same rules as a durable store: terminal runs
 * refuse resume, canonical-hash mismatches reject, event sequences must stay
 * contiguous with the committed boundary (the in-memory rendering of the §10
 * committed-event rule).
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled", "aborted");

    private final Map<String, RunRecord> runs = new HashMap<>();

    private static final class RunRecord {
        final String workflowSpecRef;
        final String canonicalSpecHash;
        String status = "running";
        long lastEventSequence;
        final Map<String, NodeCheckpoint> committedNodes = new LinkedHashMap<>();
        InFlightAttempt inFlight;
        final List<WorkflowEvent> journal = new ArrayList<>();
        Instant updatedAt;

        RunRecord(String workflowSpecRef, String canonicalSpecHash, Instant now) {
            this.workflowSpecRef = workflowSpecRef;
            this.canonicalSpecHash = canonicalSpecHash;
            this.updatedAt = now;
        }
    }

    @Override
    public synchronized RunState openRun(String workflowRunId, String workflowSpecRef,
            String canonicalSpecHash) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(workflowSpecRef, "workflowSpecRef");
        Objects.requireNonNull(canonicalSpecHash, "canonicalSpecHash");
        RunRecord run = runs.get(workflowRunId);
        if (run == null) {
            runs.put(workflowRunId, new RunRecord(workflowSpecRef, canonicalSpecHash, Instant.now()));
            return new RunState(false, 0, List.of(), Optional.empty());
        }
        if (TERMINAL.contains(run.status)) {
            throw new ResumeRejectedException("run '" + workflowRunId + "' is terminal ("
                    + run.status + ") and refuses resume");
        }
        if (!run.canonicalSpecHash.equals(canonicalSpecHash)) {
            throw new ResumeRejectedException("canonical spec hash mismatch for run '" + workflowRunId
                    + "': a resumed run cannot continue under a changed definition");
        }
        return new RunState(true, run.lastEventSequence,
                List.copyOf(run.committedNodes.values()), Optional.ofNullable(run.inFlight));
    }

    @Override
    public synchronized void commitDispatch(String workflowRunId, DispatchRecord dispatch,
            List<WorkflowEvent> events) {
        RunRecord run = liveRun(workflowRunId);
        appendEvents(workflowRunId, run, events);
        run.inFlight = new InFlightAttempt(dispatch.nodeId(), dispatch.operationRef(),
                dispatch.attemptNumber(), false, null, dispatch.scheduledFor(), dispatch.externalRef());
    }

    @Override
    public synchronized void commitRetry(String workflowRunId, RetryRecord retry,
            List<WorkflowEvent> events) {
        RunRecord run = liveRun(workflowRunId);
        appendEvents(workflowRunId, run, events);
        String externalRef = run.inFlight != null ? run.inFlight.externalRef() : null;
        run.inFlight = new InFlightAttempt(retry.nodeId(), retry.operationRef(),
                retry.attemptNumber(), true, retry.retryDelayMillis(), retry.scheduledFor(), externalRef);
    }

    @Override
    public synchronized void commitNode(String workflowRunId, NodeCheckpoint checkpoint,
            List<WorkflowEvent> events) {
        RunRecord run = liveRun(workflowRunId);
        appendEvents(workflowRunId, run, events);
        run.committedNodes.put(checkpoint.nodeId(), checkpoint);
        run.inFlight = null;
    }

    @Override
    public synchronized void completeRun(String workflowRunId, String terminalState,
            List<WorkflowEvent> events) {
        RunRecord run = liveRun(workflowRunId);
        appendEvents(workflowRunId, run, events);
        run.status = Objects.requireNonNull(terminalState, "terminalState");
        run.inFlight = null;
    }

    @Override
    public synchronized List<ObjectNode> journal(String workflowRunId) {
        RunRecord run = runs.get(workflowRunId);
        if (run == null) {
            return List.of();
        }
        return run.journal.stream().map(WorkflowEventCodec::toJson).toList();
    }

    @Override
    public synchronized PruneResult prune(Instant olderThan, boolean terminalOnly) {
        Objects.requireNonNull(olderThan, "olderThan");
        int pruned = 0;
        Iterator<RunRecord> it = runs.values().iterator();
        while (it.hasNext()) {
            RunRecord run = it.next();
            if (run.updatedAt.isBefore(olderThan) && (!terminalOnly || TERMINAL.contains(run.status))) {
                it.remove();
                pruned++;
            }
        }
        return new PruneResult(pruned);
    }

    private RunRecord liveRun(String workflowRunId) {
        RunRecord run = runs.get(workflowRunId);
        if (run == null) {
            throw new CheckpointConflictException("unknown run: " + workflowRunId);
        }
        if (TERMINAL.contains(run.status)) {
            throw new CheckpointConflictException("run '" + workflowRunId + "' is already terminal");
        }
        return run;
    }

    private static void appendEvents(String workflowRunId, RunRecord run, List<WorkflowEvent> events) {
        long expected = run.lastEventSequence + 1;
        for (WorkflowEvent event : events) {
            if (event.sequence() != expected) {
                throw new CheckpointConflictException("event sequence gap for run '" + workflowRunId
                        + "': expected " + expected + " but got " + event.sequence()
                        + " (§10 committed-event rule)");
            }
            run.journal.add(event);
            run.lastEventSequence = expected;
            expected++;
        }
        run.updatedAt = Instant.now();
    }
}
