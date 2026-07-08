package io.github.markpollack.workflow.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Store-contract semantics every {@link CheckpointStore} implementation must share. */
class InMemoryCheckpointStoreTest {

    private final InMemoryCheckpointStore store = new InMemoryCheckpointStore();

    private WorkflowEventFactory factory(String runId) {
        return new WorkflowEventFactory(runId, "workflow://registry/t@1");
    }

    @Test
    void openRunIsFreshOnFirstOpenAndResumedThereafter() {
        CheckpointStore.RunState fresh = store.openRun("run-1", "workflow://registry/t@1", "hash-a");
        assertThat(fresh.resumed()).isFalse();
        assertThat(fresh.lastEventSequence()).isZero();
        assertThat(fresh.committedNodes()).isEmpty();
        assertThat(fresh.inFlight()).isEmpty();

        CheckpointStore.RunState resumed = store.openRun("run-1", "workflow://registry/t@1", "hash-a");
        assertThat(resumed.resumed()).isTrue();
        assertThat(resumed.lastEventSequence()).isZero(); // the ratified 0-sentinel
    }

    @Test
    void commitBoundariesAccumulateStateAndJournal() {
        WorkflowEventFactory events = factory("run-2");
        store.openRun("run-2", "workflow://registry/t@1", "hash-a");

        WorkflowEvent started = events.workflowStarted("t", "a");
        WorkflowEvent nodeStarted = events.nodeStarted("a", "task");
        WorkflowEvent dispatched = events.operationDispatched("a", "java:t.a:v1", 1, null);
        store.commitDispatch("run-2",
                new CheckpointStore.DispatchRecord("a", "java:t.a:v1", 1, null, "ext-42"),
                List.of(started, nodeStarted, dispatched));

        CheckpointStore.RunState state = store.openRun("run-2", "workflow://registry/t@1", "hash-a");
        assertThat(state.lastEventSequence()).isEqualTo(3);
        assertThat(state.inFlight()).hasValueSatisfying(inflight -> {
            assertThat(inflight.nodeId()).isEqualTo("a");
            assertThat(inflight.attemptNumber()).isEqualTo(1);
            assertThat(inflight.retryScheduled()).isFalse();
            assertThat(inflight.externalRef()).isEqualTo("ext-42");
        });

        store.commitNode("run-2",
                new CheckpointStore.NodeCheckpoint("a", "succeeded",
                        OperationResult.success("out"), Map.of("k", "v"), 1),
                List.of(events.operationSucceeded("a", "java:t.a:v1", 1, null, null)));

        state = store.openRun("run-2", "workflow://registry/t@1", "hash-a");
        assertThat(state.inFlight()).isEmpty();
        assertThat(state.committedNodes()).singleElement().satisfies(node -> {
            assertThat(node.nodeId()).isEqualTo("a");
            assertThat(node.contextWrites()).containsEntry("k", "v");
        });
        assertThat(store.journal("run-2")).hasSize(4)
                .allSatisfy(envelope -> assertThat(envelope.get("workflowRunId").asText()).isEqualTo("run-2"));
        assertThat(store.journal("run-2").get(3).get("sequence").asLong()).isEqualTo(4);
    }

    @Test
    void retryCommitMarksTheInFlightAttemptAsScheduled() {
        WorkflowEventFactory events = factory("run-3");
        store.openRun("run-3", "workflow://registry/t@1", "hash-a");
        store.commitDispatch("run-3",
                new CheckpointStore.DispatchRecord("a", "java:t.a:v1", 1, null, null),
                List.of(events.workflowStarted("t", "a"), events.nodeStarted("a", "task"),
                        events.operationDispatched("a", "java:t.a:v1", 1, null)));
        store.commitRetry("run-3",
                new CheckpointStore.RetryRecord("a", "java:t.a:v1", 1, 5000, Instant.EPOCH),
                List.of(events.operationFailed("a", "java:t.a:v1", 1,
                                OperationResult.failure(ErrorEnvelope.of("BOOM", "x", true))),
                        events.retryScheduled("a", "java:t.a:v1", 1, "policy_allows", 5000,
                                new io.github.markpollack.workflow.spec.RetryPolicySpec(2, null, null))));

        Optional<CheckpointStore.InFlightAttempt> inflight =
                store.openRun("run-3", "workflow://registry/t@1", "hash-a").inFlight();
        assertThat(inflight).hasValueSatisfying(attempt -> {
            assertThat(attempt.retryScheduled()).isTrue();
            assertThat(attempt.retryDelayMillis()).isEqualTo(5000);
            assertThat(attempt.scheduledFor()).isEqualTo(Instant.EPOCH);
        });
    }

    @Test
    void terminalRunsRefuseResume() {
        WorkflowEventFactory events = factory("run-4");
        store.openRun("run-4", "workflow://registry/t@1", "hash-a");
        store.completeRun("run-4", "completed", List.of(events.workflowStarted("t", "a")));

        assertThatExceptionOfType(ResumeRejectedException.class)
                .isThrownBy(() -> store.openRun("run-4", "workflow://registry/t@1", "hash-a"))
                .withMessageContaining("terminal");
    }

    @Test
    void canonicalHashMismatchRejectsResume() {
        store.openRun("run-5", "workflow://registry/t@1", "hash-a");
        assertThatExceptionOfType(ResumeRejectedException.class)
                .isThrownBy(() -> store.openRun("run-5", "workflow://registry/t@1", "hash-CHANGED"))
                .withMessageContaining("hash mismatch");
    }

    @Test
    void nonContiguousEventSequenceIsACommitConflict() {
        WorkflowEventFactory events = factory("run-6");
        store.openRun("run-6", "workflow://registry/t@1", "hash-a");
        events.workflowStarted("t", "a"); // sequence 1 minted but never committed
        WorkflowEvent gapped = events.nodeStarted("a", "task"); // sequence 2

        assertThatExceptionOfType(CheckpointConflictException.class)
                .isThrownBy(() -> store.commitDispatch("run-6",
                        new CheckpointStore.DispatchRecord("a", "java:t.a:v1", 1, null, null),
                        List.of(gapped)))
                .withMessageContaining("sequence gap");
    }

    @Test
    void pruneRemovesOldRunsHonoringTerminalOnly() {
        WorkflowEventFactory events = factory("run-7");
        store.openRun("run-7", "workflow://registry/t@1", "hash-a"); // stays running
        store.openRun("run-8", "workflow://registry/t@1", "hash-a");
        store.completeRun("run-8", "completed", List.of());

        Instant future = Instant.now().plusSeconds(60);
        assertThat(store.prune(future, true).runsPruned()).isEqualTo(1);
        assertThat(store.openRun("run-7", "workflow://registry/t@1", "hash-a").resumed()).isTrue();
        assertThat(store.openRun("run-8", "workflow://registry/t@1", "hash-a").resumed()).isFalse();

        assertThat(store.prune(Instant.now().plusSeconds(60), false).runsPruned()).isEqualTo(2);
    }
}
