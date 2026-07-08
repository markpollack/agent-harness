package io.github.markpollack.workflow.engine.jpa;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.workflow.engine.CanonicalSpecHash;
import io.github.markpollack.workflow.engine.CheckpointStore;
import io.github.markpollack.workflow.engine.InMemoryEventSink;
import io.github.markpollack.workflow.engine.OperationResult;
import io.github.markpollack.workflow.engine.ResumeRejectedException;
import io.github.markpollack.workflow.engine.SimpleOperationRegistry;
import io.github.markpollack.workflow.engine.WorkflowEvent;
import io.github.markpollack.workflow.engine.WorkflowEventSink;
import io.github.markpollack.workflow.engine.WorkflowEventType;
import io.github.markpollack.workflow.engine.WorkflowInterpreter;
import io.github.markpollack.workflow.engine.WorkflowRunOutcome;
import io.github.markpollack.workflow.spec.AlwaysCondition;
import io.github.markpollack.workflow.spec.BackoffSpec;
import io.github.markpollack.workflow.spec.Binding;
import io.github.markpollack.workflow.spec.OperationDeclaration;
import io.github.markpollack.workflow.spec.PolicyBundle;
import io.github.markpollack.workflow.spec.RetryPolicySpec;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.TerminateSpecNode;
import io.github.markpollack.workflow.spec.TerminateStatus;
import io.github.markpollack.workflow.spec.WorkflowEdgeSpec;
import io.github.markpollack.workflow.spec.WorkflowMetadata;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import io.github.markpollack.workflow.spec.WorkflowSpecNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Stage-3 star scenario against the real JPA store: crash mid-workflow after node
 * N commits, resume, and prove the precise crash-recovery promise — committed
 * completed nodes never re-execute; the in-flight attempt normalizes to
 * {@code INTERRUPTED} through ordinary §17; the journal is one coherent §10 stream
 * across incarnations; and no checkpoint-without-events or events-without-checkpoint
 * state is observable (review-21 atomicity rule).
 *
 * <p>{@code Propagation.NOT_SUPPORTED} suspends {@code @DataJpaTest}'s test
 * transaction so every store boundary commits for real — the crash loses exactly the
 * uncommitted buffer, nothing more, which is the whole point of the test.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DurableInterpreterCrashRecoveryIT {

    @Autowired
    private JpaCheckpointStore store;

    private final AtomicInteger aRuns = new AtomicInteger();
    private final AtomicInteger bRuns = new AtomicInteger();

    private WorkflowSpec twoTaskSpec(String name) {
        PolicyBundle bPolicies = new PolicyBundle(
                new RetryPolicySpec(3, new BackoffSpec(BackoffSpec.Strategy.FIXED, 1, null, null), null),
                null);
        List<WorkflowSpecNode> nodes = List.of(
                new TaskSpecNode("a", null, null, null, "op-a", null,
                        Map.of("a.result", new Binding("$node.a.output")), null),
                new TaskSpecNode("b", null, null, null, "op-b", null, null, bPolicies),
                new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED,
                        new Binding("$node.b.output")));
        List<WorkflowEdgeSpec> edges = List.of(
                new WorkflowEdgeSpec("a", "b", new AlwaysCondition(), null),
                new WorkflowEdgeSpec("b", "done", new AlwaysCondition(), null));
        return new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata(name, "1.0.0", null, null),
                null, null, null,
                Map.of("op-a", new OperationDeclaration("java:t.a:v1", null, null, null, null, null),
                        "op-b", new OperationDeclaration("java:t.b:v1", null, null, null, null, null)),
                nodes, edges, null, "a", null);
    }

    private SimpleOperationRegistry registry() {
        return new SimpleOperationRegistry()
                .register("java:t.a:v1", (inv, ctx, in) -> {
                    aRuns.incrementAndGet();
                    return OperationResult.success("a-out");
                })
                .register("java:t.b:v1", (inv, ctx, in) -> {
                    bRuns.incrementAndGet();
                    return OperationResult.success("b-out");
                });
    }

    /** Simulates a process crash: the observer sink throws on the first matching event. */
    private static WorkflowEventSink crashingOn(Predicate<WorkflowEvent> crashOn) {
        InMemoryEventSink delegate = new InMemoryEventSink();
        return new WorkflowEventSink() {
            private boolean crashed;

            @Override
            public void emit(WorkflowEvent event) {
                if (!crashed && crashOn.test(event)) {
                    crashed = true;
                    throw new IllegalStateException("simulated crash");
                }
                delegate.emit(event);
            }
        };
    }

    @Test
    void crashAfterNodeCommitsResumesWithoutReExecutingCommittedNodes() {
        WorkflowSpec spec = twoTaskSpec("crash-recovery");
        String runId = "run-crash-jpa";

        // incarnation 1: node a completes and commits; b's attempt 1 runs but the
        // process dies before its result commits
        WorkflowInterpreter first = new WorkflowInterpreter(registry(),
                crashingOn(e -> e.eventType() == WorkflowEventType.OPERATION_SUCCEEDED
                        && "b".equals(e.nodeId())),
                java.time.Clock.systemUTC(), store);
        assertThatThrownBy(() -> first.run(spec, runId, null)).hasMessage("simulated crash");

        // mid-crash, the committed boundary is coherent: a checkpointed with its
        // context writes; b in flight at attempt 1; journal ends at the dispatch
        CheckpointStore.RunState midCrash = store.openRun(runId,
                "workflow://registry/crash-recovery@1.0.0", CanonicalSpecHash.of(spec));
        assertThat(midCrash.committedNodes()).singleElement().satisfies(node -> {
            assertThat(node.nodeId()).isEqualTo("a");
            assertThat(node.contextWrites()).containsEntry("a.result", "a-out");
        });
        assertThat(midCrash.inFlight()).hasValueSatisfying(inflight -> {
            assertThat(inflight.nodeId()).isEqualTo("b");
            assertThat(inflight.attemptNumber()).isEqualTo(1);
        });
        assertNoCheckpointWithoutEventsOrEventsWithoutCheckpoint(runId, midCrash);

        // incarnation 2: resume completes the run
        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                java.time.Clock.systemUTC(), store).run(spec, runId, null);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("b-out");
        assertThat(aRuns).hasValue(1); // committed nodes never re-execute
        assertThat(bRuns).hasValue(2); // the in-flight attempt retried as INTERRUPTED

        // the journal is one conformant stream across both incarnations
        List<ObjectNode> journal = store.journal(runId);
        for (int i = 0; i < journal.size(); i++) {
            assertThat(journal.get(i).get("sequence").asLong()).isEqualTo(i + 1);
        }
        List<String> types = journal.stream().map(e -> e.get("eventType").asText()).toList();
        assertThat(types.get(types.size() - 1)).isEqualTo("WorkflowCompleted");
        assertThat(types.stream().filter("WorkflowStarted"::equals)).hasSize(1);
        assertThat(types.stream().filter(t -> t.equals("WorkflowCompleted")
                || t.equals("WorkflowFailed") || t.equals("WorkflowCancelled")
                || t.equals("WorkflowAborted"))).hasSize(1);
        // b's history shows the INTERRUPTED normalization flowing through ordinary §17
        List<String> bTypes = journal.stream()
                .filter(e -> e.has("nodeId") && e.get("nodeId").asText().equals("b"))
                .map(e -> e.get("eventType").asText()).toList();
        assertThat(bTypes).containsExactly("NodeStarted", "OperationDispatched", "OperationFailed",
                "RetryScheduled", "OperationDispatched", "OperationSucceeded", "NodeCompleted",
                "EdgeSelected");
    }

    /**
     * Review-21 atomicity: every committed checkpoint's {@code lastEventSequence} lies
     * within the committed journal, and the journal never runs ahead of the run row's
     * committed boundary — neither checkpoint-without-events nor
     * events-without-checkpoint is observable after a crash.
     */
    private void assertNoCheckpointWithoutEventsOrEventsWithoutCheckpoint(String runId,
            CheckpointStore.RunState state) {
        List<ObjectNode> journal = store.journal(runId);
        assertThat(journal).hasSize((int) state.lastEventSequence());
        for (int i = 0; i < journal.size(); i++) {
            assertThat(journal.get(i).get("sequence").asLong()).isEqualTo(i + 1);
        }
    }

    @Test
    void sameSpecRunsUnchangedOnEphemeralAndJpaDurableInterpreters() {
        // VISION success criterion 5: one spec, both interpreters, identical behavior
        WorkflowSpec spec = twoTaskSpec("equivalence");

        InMemoryEventSink ephemeralSink = new InMemoryEventSink();
        WorkflowRunOutcome ephemeral = new WorkflowInterpreter(registry(), ephemeralSink)
                .run(spec, "run-eq-jpa", null);

        InMemoryEventSink durableSink = new InMemoryEventSink();
        WorkflowRunOutcome durable = new WorkflowInterpreter(registry(), durableSink,
                java.time.Clock.systemUTC(), store).run(spec, "run-eq-jpa", null);

        assertThat(durable).isEqualTo(ephemeral);
        assertThat(project(durableSink)).isEqualTo(project(ephemeralSink));

        // and the committed journal mirrors the live stream
        assertThat(store.journal("run-eq-jpa")).hasSize(durableSink.events().size());
    }

    @Test
    void terminalRunRefusesResumeAndChangedSpecIsRejected() {
        WorkflowSpec spec = twoTaskSpec("pinning");
        WorkflowInterpreter interpreter = new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                java.time.Clock.systemUTC(), store);
        assertThat(interpreter.run(spec, "run-pin-jpa", null).completed()).isTrue();

        assertThatExceptionOfType(ResumeRejectedException.class)
                .isThrownBy(() -> interpreter.run(spec, "run-pin-jpa", null))
                .withMessageContaining("terminal");

        // a fresh crash-interrupted run, then a changed definition: hash pinning rejects
        WorkflowSpec crashSpec = twoTaskSpec("pinning-crash");
        WorkflowInterpreter crashing = new WorkflowInterpreter(registry(),
                crashingOn(e -> e.eventType() == WorkflowEventType.OPERATION_SUCCEEDED
                        && "b".equals(e.nodeId())),
                java.time.Clock.systemUTC(), store);
        assertThatThrownBy(() -> crashing.run(crashSpec, "run-pin-crash", null))
                .hasMessage("simulated crash");

        WorkflowSpec edited = new WorkflowSpec(crashSpec.apiVersion(), crashSpec.kind(),
                new WorkflowMetadata("pinning-crash", "1.0.1", null, null),
                null, null, null, crashSpec.operations(), crashSpec.nodes(), crashSpec.edges(),
                null, crashSpec.entrypoint(), crashSpec.outputs());
        assertThatExceptionOfType(ResumeRejectedException.class)
                .isThrownBy(() -> new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                        java.time.Clock.systemUTC(), store).run(edited, "run-pin-crash", null))
                .withMessageContaining("hash mismatch");
    }

    private static List<String> project(InMemoryEventSink sink) {
        return sink.events().stream()
                .map(e -> e.sequence() + ":" + e.eventType() + ":" + e.nodeId())
                .toList();
    }
}
