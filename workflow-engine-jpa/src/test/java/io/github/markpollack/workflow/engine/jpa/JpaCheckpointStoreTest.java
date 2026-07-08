package io.github.markpollack.workflow.engine.jpa;

import io.github.markpollack.workflow.engine.CheckpointConflictException;
import io.github.markpollack.workflow.engine.CheckpointStore;
import io.github.markpollack.workflow.engine.ErrorEnvelope;
import io.github.markpollack.workflow.engine.OperationResult;
import io.github.markpollack.workflow.engine.ResumeRejectedException;
import io.github.markpollack.workflow.engine.WorkflowEvent;
import io.github.markpollack.workflow.engine.WorkflowEventFactory;
import io.github.markpollack.workflow.spec.RetryPolicySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link JpaCheckpointStore} against H2 (workflow-batch idiom): the shared store
 * contract — open/resume/refusal, boundary lifecycle, journal round-trip, contiguity
 * conflicts, optimistic versioning, prune.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaTestConfig.class)
class JpaCheckpointStoreTest {

    @Autowired
    private JpaCheckpointStore store;

    @Autowired
    private TestEntityManager em;

    private WorkflowEventFactory factory(String runId) {
        return new WorkflowEventFactory(runId, "workflow://registry/t@1");
    }

    @Test
    void openRunIsFreshOnFirstOpenAndResumedThereafter() {
        CheckpointStore.RunState fresh = store.openRun("run-1", "workflow://registry/t@1", "hash-a");
        assertThat(fresh.resumed()).isFalse();
        assertThat(fresh.lastEventSequence()).isZero();

        CheckpointStore.RunState resumed = store.openRun("run-1", "workflow://registry/t@1", "hash-a");
        assertThat(resumed.resumed()).isTrue();
        assertThat(resumed.lastEventSequence()).isZero(); // the ratified 0-sentinel
    }

    @Test
    void boundaryLifecycleRoundTripsThroughTheDatabase() {
        WorkflowEventFactory events = factory("run-2");
        store.openRun("run-2", "workflow://registry/t@1", "hash-a");

        store.commitDispatch("run-2",
                new CheckpointStore.DispatchRecord("a", "java:t.a:v1", 1, null, "session-77"),
                List.of(events.workflowStarted("t", "a"), events.nodeStarted("a", "task"),
                        events.operationDispatched("a", "java:t.a:v1", 1, null)));
        em.clear(); // read back from the database, not the persistence context

        CheckpointStore.RunState state = store.openRun("run-2", "workflow://registry/t@1", "hash-a");
        assertThat(state.lastEventSequence()).isEqualTo(3);
        assertThat(state.inFlight()).hasValueSatisfying(inflight -> {
            assertThat(inflight.nodeId()).isEqualTo("a");
            assertThat(inflight.retryScheduled()).isFalse();
            assertThat(inflight.externalRef()).isEqualTo("session-77"); // the recovery slot
        });

        store.commitRetry("run-2",
                new CheckpointStore.RetryRecord("a", "java:t.a:v1", 1, 5000, Instant.EPOCH),
                List.of(events.operationFailed("a", "java:t.a:v1", 1,
                                OperationResult.failure(ErrorEnvelope.of("BOOM", "x", true))),
                        events.retryScheduled("a", "java:t.a:v1", 1, "policy_allows", 5000,
                                new RetryPolicySpec(2, null, null))));
        em.clear();

        state = store.openRun("run-2", "workflow://registry/t@1", "hash-a");
        assertThat(state.inFlight()).hasValueSatisfying(inflight -> {
            assertThat(inflight.retryScheduled()).isTrue();
            assertThat(inflight.retryDelayMillis()).isEqualTo(5000);
            assertThat(inflight.scheduledFor()).isEqualTo(Instant.EPOCH);
        });

        // attempt 2's dispatch carries no handle — the committed one must survive the increment
        store.commitDispatch("run-2",
                new CheckpointStore.DispatchRecord("a", "java:t.a:v1", 2, Instant.EPOCH, null),
                List.of(events.operationDispatched("a", "java:t.a:v1", 2, Instant.EPOCH)));
        em.clear();
        assertThat(store.openRun("run-2", "workflow://registry/t@1", "hash-a").inFlight())
                .hasValueSatisfying(inflight -> assertThat(inflight.externalRef())
                        .isEqualTo("session-77"));

        store.commitNode("run-2",
                new CheckpointStore.NodeCheckpoint("a", "succeeded",
                        OperationResult.success(Map.of("answer", 42)), Map.of("k", "v"), 2),
                List.of(events.operationSucceeded("a", "java:t.a:v1", 2, null, null),
                        events.nodeCompleted("a", "succeeded")));
        em.clear();

        state = store.openRun("run-2", "workflow://registry/t@1", "hash-a");
        assertThat(state.inFlight()).isEmpty();
        assertThat(state.committedNodes()).singleElement().satisfies(node -> {
            assertThat(node.state()).isEqualTo("succeeded");
            assertThat(node.attempts()).isEqualTo(2);
            assertThat(node.contextWrites()).containsEntry("k", "v");
            // wire-framed result: JSON-natural Java values on the way back
            assertThat(((OperationResult.Success) node.result()).output())
                    .isEqualTo(Map.of("answer", 42));
        });

        assertThat(store.journal("run-2")).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(store.journal("run-2").get(i).get("sequence").asLong()).isEqualTo(i + 1);
        }
    }

    @Test
    void terminalRunsRefuseResume() {
        WorkflowEventFactory events = factory("run-3");
        store.openRun("run-3", "workflow://registry/t@1", "hash-a");
        store.completeRun("run-3", "completed", List.of(events.workflowStarted("t", "a")));
        em.clear();

        assertThatExceptionOfType(ResumeRejectedException.class)
                .isThrownBy(() -> store.openRun("run-3", "workflow://registry/t@1", "hash-a"))
                .withMessageContaining("terminal");
    }

    @Test
    void canonicalHashMismatchRejectsResume() {
        store.openRun("run-4", "workflow://registry/t@1", "hash-a");
        assertThatExceptionOfType(ResumeRejectedException.class)
                .isThrownBy(() -> store.openRun("run-4", "workflow://registry/t@1", "hash-CHANGED"))
                .withMessageContaining("hash mismatch");
    }

    @Test
    void nonContiguousEventSequenceIsACommitConflict() {
        WorkflowEventFactory events = factory("run-5");
        store.openRun("run-5", "workflow://registry/t@1", "hash-a");
        events.workflowStarted("t", "a"); // sequence 1 minted but never committed
        WorkflowEvent gapped = events.nodeStarted("a", "task"); // sequence 2

        assertThatExceptionOfType(CheckpointConflictException.class)
                .isThrownBy(() -> store.commitDispatch("run-5",
                        new CheckpointStore.DispatchRecord("a", "java:t.a:v1", 1, null, null),
                        List.of(gapped)))
                .withMessageContaining("sequence gap");
    }

    @Test
    void everyCommitBoundaryAdvancesTheOptimisticVersion() {
        WorkflowEventFactory events = factory("run-6");
        store.openRun("run-6", "workflow://registry/t@1", "hash-a");
        long v0 = runVersion("run-6");

        store.commitDispatch("run-6",
                new CheckpointStore.DispatchRecord("a", "java:t.a:v1", 1, null, null),
                List.of(events.workflowStarted("t", "a"), events.nodeStarted("a", "task"),
                        events.operationDispatched("a", "java:t.a:v1", 1, null)));
        long v1 = runVersion("run-6");

        store.commitNode("run-6",
                new CheckpointStore.NodeCheckpoint("a", "succeeded",
                        OperationResult.success("out"), Map.of(), 1),
                List.of(events.operationSucceeded("a", "java:t.a:v1", 1, null, null)));
        long v2 = runVersion("run-6");

        // every boundary is a version-guarded write: two runners advancing one run
        // collide on @Version instead of interleaving silently (D1 claim mechanics)
        assertThat(v1).isGreaterThan(v0);
        assertThat(v2).isGreaterThan(v1);
    }

    private long runVersion(String runId) {
        em.flush();
        return em.getEntityManager()
                .createQuery("SELECT r.version FROM WorkflowRunEntity r WHERE r.workflowRunId = :id",
                        Long.class)
                .setParameter("id", runId)
                .getSingleResult();
    }

    @Test
    void pruneRemovesOldRunsWithJournalAndCheckpointsHonoringTerminalOnly() {
        WorkflowEventFactory events = factory("run-7");
        store.openRun("run-7", "workflow://registry/t@1", "hash-a"); // stays running
        store.openRun("run-8", "workflow://registry/t@1", "hash-a");
        store.completeRun("run-8", "completed", List.of(events.workflowStarted("t", "a")));
        em.flush();

        Instant future = Instant.now().plusSeconds(60);
        assertThat(store.prune(future, true).runsPruned()).isEqualTo(1);
        em.clear(); // JPQL bulk deletes bypass the test's shared persistence context
        assertThat(store.journal("run-8")).isEmpty();
        assertThat(store.openRun("run-7", "workflow://registry/t@1", "hash-a").resumed()).isTrue();

        assertThat(store.prune(future, false).runsPruned()).isEqualTo(1);
        em.clear();
        assertThat(store.openRun("run-7", "workflow://registry/t@1", "hash-a").resumed()).isFalse();
    }
}
