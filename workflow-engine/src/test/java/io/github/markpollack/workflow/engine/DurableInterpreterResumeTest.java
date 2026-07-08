package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The durable interpreter against {@link InMemoryCheckpointStore}: the precise
 * crash-recovery promise (committed completed nodes never re-execute; in-flight
 * attempts may retry as {@code INTERRUPTED} through ordinary §17), stale-retry
 * immediate firing, run-identity pinning, terminal-refusal, and
 * ephemeral/durable stream equivalence.
 */
class DurableInterpreterResumeTest {

    // ---------------------------------------------------------------------
    // Fixture: a → b → done, with a retry policy on b so INTERRUPTED can re-attempt
    // ---------------------------------------------------------------------

    private final AtomicInteger aRuns = new AtomicInteger();
    private final AtomicInteger bRuns = new AtomicInteger();

    private WorkflowSpec twoTaskSpec() {
        PolicyBundle bPolicies = new PolicyBundle(
                new RetryPolicySpec(3, new BackoffSpec(BackoffSpec.Strategy.FIXED, 1, null, null), null),
                null);
        List<WorkflowSpecNode> nodes = List.of(
                new TaskSpecNode("a", null, null, null, "op-a", null, null, null),
                new TaskSpecNode("b", null, null, null, "op-b", null, null, bPolicies),
                new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED,
                        new Binding("$node.b.output")));
        List<WorkflowEdgeSpec> edges = List.of(
                new WorkflowEdgeSpec("a", "b", new AlwaysCondition(), null),
                new WorkflowEdgeSpec("b", "done", new AlwaysCondition(), null));
        return new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("durable-two-task", "1.0.0", null, null),
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

    /** An observer sink that simulates a crash by throwing on the first matching event. */
    private static final class CrashingSink implements WorkflowEventSink {
        private final InMemoryEventSink delegate = new InMemoryEventSink();
        private final Predicate<WorkflowEvent> crashOn;
        private boolean crashed;

        CrashingSink(Predicate<WorkflowEvent> crashOn) {
            this.crashOn = crashOn;
        }

        @Override
        public void emit(WorkflowEvent event) {
            if (!crashed && crashOn.test(event)) {
                crashed = true;
                throw new IllegalStateException("simulated crash");
            }
            delegate.emit(event);
        }
    }

    private static void assertContiguousJournalWithSingleTerminal(List<ObjectNode> journal) {
        for (int i = 0; i < journal.size(); i++) {
            assertThat(journal.get(i).get("sequence").asLong()).isEqualTo(i + 1);
        }
        List<String> types = journal.stream().map(e -> e.get("eventType").asText()).toList();
        assertThat(types.stream().filter(t -> t.startsWith("Workflow") && !t.equals("WorkflowStarted")))
                .hasSize(1);
        assertThat(types.get(types.size() - 1)).isEqualTo("WorkflowCompleted");
        assertThat(types.stream().filter(t -> t.equals("WorkflowStarted"))).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // Crash recovery — the star scenario
    // ---------------------------------------------------------------------

    @Test
    void committedNodesAreSkippedAndInFlightAttemptRetriesAsInterrupted() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = twoTaskSpec();

        // incarnation 1 crashes emitting b's OperationSucceeded — b attempt 1 ran to
        // completion, but its result was never committed (dispatched-without-result)
        CrashingSink crashing = new CrashingSink(e ->
                e.eventType() == WorkflowEventType.OPERATION_SUCCEEDED && "b".equals(e.nodeId()));
        WorkflowInterpreter first = new WorkflowInterpreter(registry(), crashing, Clock.systemUTC(), store);
        assertThatThrownBy(() -> first.run(spec, "run-crash", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated crash");

        // the committed boundary is coherent mid-crash: node a checkpointed, b in flight
        CheckpointStore.RunState midCrash = store.openRun("run-crash",
                WorkflowInterpreter.specRef(spec), CanonicalSpecHash.of(spec));
        assertThat(midCrash.committedNodes()).extracting(CheckpointStore.NodeCheckpoint::nodeId)
                .containsExactly("a");
        assertThat(midCrash.inFlight()).hasValueSatisfying(inflight -> {
            assertThat(inflight.nodeId()).isEqualTo("b");
            assertThat(inflight.attemptNumber()).isEqualTo(1);
            assertThat(inflight.retryScheduled()).isFalse();
        });

        // incarnation 2 resumes: a never re-dispatches; b normalizes to INTERRUPTED
        // at attempt 1 and re-attempts under its ordinary retry policy
        InMemoryEventSink resumeSink = new InMemoryEventSink();
        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry(), resumeSink,
                Clock.systemUTC(), store).run(spec, "run-crash", null);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("b-out");
        assertThat(aRuns).hasValue(1); // the precise promise: committed nodes never re-execute
        assertThat(bRuns).hasValue(2); // in-flight attempts may retry — idempotency is the operation's

        // the resumed incarnation's first event is the INTERRUPTED normalization
        WorkflowEvent firstResumed = resumeSink.events().get(0);
        assertThat(firstResumed.eventType()).isEqualTo(WorkflowEventType.OPERATION_FAILED);
        assertThat(firstResumed.nodeId()).isEqualTo("b");
        assertThat(firstResumed.attemptNumber()).isEqualTo(1);
        assertThat(firstResumed.payload().get("error").toString()).contains("INTERRUPTED");

        // the journal is one coherent stream across incarnations
        List<ObjectNode> journal = store.journal("run-crash");
        assertContiguousJournalWithSingleTerminal(journal);
        List<String> bTypes = journal.stream()
                .filter(e -> e.has("nodeId") && e.get("nodeId").asText().equals("b"))
                .map(e -> e.get("eventType").asText())
                .toList();
        assertThat(bTypes).containsExactly("NodeStarted", "OperationDispatched", "OperationFailed",
                "RetryScheduled", "OperationDispatched", "OperationSucceeded", "NodeCompleted",
                "EdgeSelected");
    }

    @Test
    void staleCommittedRetryFiresImmediatelyOnResume() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();

        // b: fails on attempt 1, would succeed on attempt 2 — with a ONE HOUR delay
        PolicyBundle hourRetry = new PolicyBundle(
                new RetryPolicySpec(2, new BackoffSpec(BackoffSpec.Strategy.FIXED, 3_600_000, null, null),
                        null),
                null);
        WorkflowSpec spec = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("stale-retry", "1.0.0", null, null),
                null, null, null,
                Map.of("op-b", new OperationDeclaration("java:t.flaky:v1", null, null, null, null, null)),
                List.of(new TaskSpecNode("b", null, null, null, "op-b", null, null, hourRetry),
                        new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED,
                                new Binding("$node.b.output"))),
                List.of(new WorkflowEdgeSpec("b", "done", new AlwaysCondition(), null)),
                null, "b", null);
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:t.flaky:v1", (inv, ctx, in) -> {
                    int attempt = bRuns.incrementAndGet();
                    return attempt == 1
                            ? OperationResult.failure(ErrorEnvelope.of("FLAKY", "first attempt fails", true))
                            : OperationResult.success("recovered");
                });

        // incarnation 1 crashes right after the retry boundary commits — before sleeping
        CheckpointStore crashAfterRetry = new CrashAfterRetryCommitStore(store);
        assertThatThrownBy(() -> new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), crashAfterRetry).run(spec, "run-stale", null))
                .hasMessage("simulated crash after retry commit");

        // resume: the committed RetryScheduled is stale — attempt 2 fires immediately,
        // not an hour later
        long start = System.nanoTime();
        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), store).run(spec, "run-stale", null);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("recovered");
        assertThat(bRuns).hasValue(2);
        assertThat(elapsedMillis).isLessThan(5_000);

        // no second RetryScheduled: the committed one is honored, not re-planned
        List<String> types = store.journal("run-stale").stream()
                .map(e -> e.get("eventType").asText()).toList();
        assertThat(types.stream().filter("RetryScheduled"::equals)).hasSize(1);
        assertContiguousJournalWithSingleTerminal(store.journal("run-stale"));
    }

    /** Delegates everything; throws after a successful retry commit (crash-before-sleep). */
    private static final class CrashAfterRetryCommitStore implements CheckpointStore {
        private final CheckpointStore delegate;

        CrashAfterRetryCommitStore(CheckpointStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public RunState openRun(String runId, String specRef, String hash) {
            return delegate.openRun(runId, specRef, hash);
        }

        @Override
        public void commitDispatch(String runId, DispatchRecord dispatch, List<WorkflowEvent> events) {
            delegate.commitDispatch(runId, dispatch, events);
        }

        @Override
        public void commitRetry(String runId, RetryRecord retry, List<WorkflowEvent> events) {
            delegate.commitRetry(runId, retry, events);
            throw new IllegalStateException("simulated crash after retry commit");
        }

        @Override
        public void commitNode(String runId, NodeCheckpoint checkpoint, List<WorkflowEvent> events) {
            delegate.commitNode(runId, checkpoint, events);
        }

        @Override
        public void completeRun(String runId, String terminalState, List<WorkflowEvent> events) {
            delegate.completeRun(runId, terminalState, events);
        }

        @Override
        public List<ObjectNode> journal(String runId) {
            return delegate.journal(runId);
        }

        @Override
        public PruneResult prune(Instant olderThan, boolean terminalOnly) {
            return delegate.prune(olderThan, terminalOnly);
        }
    }

    // ---------------------------------------------------------------------
    // Run-identity pinning and terminal refusal
    // ---------------------------------------------------------------------

    @Test
    void terminalRunRefusesResume() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = twoTaskSpec();
        WorkflowInterpreter interpreter = new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), store);
        assertThat(interpreter.run(spec, "run-done", null).completed()).isTrue();

        assertThatExceptionOfType(ResumeRejectedException.class)
                .isThrownBy(() -> interpreter.run(spec, "run-done", null))
                .withMessageContaining("terminal");
    }

    @Test
    void resumeWithAChangedSpecIsRejectedByCanonicalHashPinning() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = twoTaskSpec();
        CrashingSink crashing = new CrashingSink(e ->
                e.eventType() == WorkflowEventType.OPERATION_SUCCEEDED && "b".equals(e.nodeId()));
        assertThatThrownBy(() -> new WorkflowInterpreter(registry(), crashing, Clock.systemUTC(), store)
                .run(spec, "run-pinned", null)).hasMessage("simulated crash");

        WorkflowSpec edited = new WorkflowSpec(spec.apiVersion(), spec.kind(),
                new WorkflowMetadata("durable-two-task", "1.0.1", null, null), // changed definition
                null, null, null, spec.operations(), spec.nodes(), spec.edges(), null,
                spec.entrypoint(), spec.outputs());

        assertThatExceptionOfType(ResumeRejectedException.class)
                .isThrownBy(() -> new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                        Clock.systemUTC(), store).run(edited, "run-pinned", null))
                .withMessageContaining("hash mismatch");
    }

    // ---------------------------------------------------------------------
    // Ephemeral/durable equivalence (VISION success criterion 5, engine side)
    // ---------------------------------------------------------------------

    @Test
    void sameSpecEmitsIdenticalStreamsOnEphemeralAndDurableInterpreters() {
        WorkflowSpec spec = twoTaskSpec();

        InMemoryEventSink ephemeralSink = new InMemoryEventSink();
        WorkflowRunOutcome ephemeral = new WorkflowInterpreter(registry(), ephemeralSink)
                .run(spec, "run-eq", null);

        InMemoryEventSink durableSink = new InMemoryEventSink();
        WorkflowRunOutcome durable = new WorkflowInterpreter(registry(), durableSink,
                Clock.systemUTC(), new InMemoryCheckpointStore()).run(spec, "run-eq", null);

        assertThat(durable).isEqualTo(ephemeral);
        assertThat(project(durableSink)).isEqualTo(project(ephemeralSink));
    }

    private static List<String> project(InMemoryEventSink sink) {
        return sink.events().stream()
                .map(e -> e.sequence() + ":" + e.eventType() + ":" + e.nodeId())
                .toList();
    }
}
