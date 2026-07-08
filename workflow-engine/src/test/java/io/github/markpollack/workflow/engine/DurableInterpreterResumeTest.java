package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.workflow.spec.AlwaysCondition;
import io.github.markpollack.workflow.spec.BackoffSpec;
import io.github.markpollack.workflow.spec.Binding;
import io.github.markpollack.workflow.spec.DecisionResultCondition;
import io.github.markpollack.workflow.spec.DecisionSpecNode;
import io.github.markpollack.workflow.spec.ErrorCondition;
import io.github.markpollack.workflow.spec.ErrorMatch;
import io.github.markpollack.workflow.spec.ErrorMatcher;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The durable interpreter against {@link InMemoryCheckpointStore}: the precise
 * crash-recovery promise (committed completed nodes never re-execute; in-flight
 * attempts may retry as {@code INTERRUPTED} through ordinary §17), §17 resume misfire
 * semantics (stale retries fire now, not-yet-due retries wait out their remaining
 * delay), silent replay of committed routing, terminate re-execution, run-identity
 * pinning, graceful-shutdown resumability, and ephemeral/durable stream equivalence.
 *
 * <p>Crashes are simulated at store boundaries: a {@link CrashBeforeStore} throws
 * instead of committing — everything before the boundary is durable, everything after
 * is lost, exactly like a process death between commits.
 */
class DurableInterpreterResumeTest {

    // ---------------------------------------------------------------------
    // Fixture: a → b → done, with a retry policy on b so INTERRUPTED can re-attempt
    // ---------------------------------------------------------------------

    private final AtomicInteger aRuns = new AtomicInteger();
    private final AtomicInteger bRuns = new AtomicInteger();

    private static PolicyBundle fixedRetry(int maxAttempts, long delayMillis) {
        return new PolicyBundle(new RetryPolicySpec(maxAttempts,
                new BackoffSpec(BackoffSpec.Strategy.FIXED, delayMillis, null, null), null), null);
    }

    private WorkflowSpec twoTaskSpec() {
        List<WorkflowSpecNode> nodes = List.of(
                new TaskSpecNode("a", null, null, null, "op-a", null, null, null),
                new TaskSpecNode("b", null, null, null, "op-b", null, null, fixedRetry(3, 1)),
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

    private static void assertContiguousJournalWithSingleTerminal(List<ObjectNode> journal) {
        for (int i = 0; i < journal.size(); i++) {
            assertThat(journal.get(i).get("sequence").asLong()).isEqualTo(i + 1);
        }
        List<String> types = journal.stream().map(e -> e.get("eventType").asText()).toList();
        assertThat(types.stream().filter(t -> t.startsWith("Workflow") && !t.equals("WorkflowStarted")))
                .hasSize(1);
        assertThat(types.get(types.size() - 1)).startsWith("Workflow");
        assertThat(types.stream().filter("WorkflowStarted"::equals)).hasSize(1);
    }

    /**
     * Simulates a process death at a commit boundary: throws INSTEAD of committing the
     * first matching call. Everything committed before stays durable; the boundary's
     * events are lost with the process.
     */
    private static class CrashBeforeStore implements CheckpointStore {
        private final CheckpointStore delegate;
        private final String method;
        private final String nodeId; // null = any node
        private boolean armed = true;

        CrashBeforeStore(CheckpointStore delegate, String method, String nodeId) {
            this.delegate = delegate;
            this.method = method;
            this.nodeId = nodeId;
        }

        private void maybeCrash(String calledMethod, String calledNode) {
            if (armed && method.equals(calledMethod)
                    && (nodeId == null || nodeId.equals(calledNode))) {
                armed = false;
                throw new IllegalStateException("simulated crash");
            }
        }

        @Override
        public RunState openRun(String runId, String specRef, String hash) {
            return delegate.openRun(runId, specRef, hash);
        }

        @Override
        public void commitDispatch(String runId, DispatchRecord dispatch, List<WorkflowEvent> events) {
            maybeCrash("commitDispatch", dispatch.nodeId());
            delegate.commitDispatch(runId, dispatch, events);
        }

        @Override
        public void commitRetry(String runId, RetryRecord retry, List<WorkflowEvent> events) {
            maybeCrash("commitRetry", retry.nodeId());
            delegate.commitRetry(runId, retry, events);
        }

        @Override
        public void commitNode(String runId, NodeCheckpoint checkpoint, List<WorkflowEvent> events) {
            maybeCrash("commitNode", checkpoint.nodeId());
            delegate.commitNode(runId, checkpoint, events);
        }

        @Override
        public void completeRun(String runId, String terminalState, List<WorkflowEvent> events) {
            maybeCrash("completeRun", null);
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

    /** Delegates everything; throws after a successful retry commit (crash-before-sleep). */
    private static final class CrashAfterRetryCommitStore extends CrashBeforeStore {
        private final CheckpointStore delegate;
        private boolean armed = true;

        CrashAfterRetryCommitStore(CheckpointStore delegate) {
            super(delegate, "none", null);
            this.delegate = delegate;
        }

        @Override
        public void commitRetry(String runId, RetryRecord retry, List<WorkflowEvent> events) {
            delegate.commitRetry(runId, retry, events);
            if (armed) {
                armed = false;
                throw new IllegalStateException("simulated crash after retry commit");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Crash recovery — the star scenario
    // ---------------------------------------------------------------------

    @Test
    void committedNodesAreSkippedAndInFlightAttemptRetriesAsInterrupted() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = twoTaskSpec();

        // incarnation 1 dies at b's node-commit boundary: b attempt 1 ran to
        // completion, but its result was never committed (dispatched-without-result)
        WorkflowInterpreter first = new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), new CrashBeforeStore(store, "commitNode", "b"));
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

        // the resumed incarnation's first published event is the INTERRUPTED normalization
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
    void firstNodeInFlightResumesFromEmptyCommittedNodes() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("single-task", "1.0.0", null, null),
                null, null, null,
                Map.of("op-a", new OperationDeclaration("java:t.a:v1", null, null, null, null, null)),
                List.of(new TaskSpecNode("a", null, null, null, "op-a", null, null, fixedRetry(2, 1)),
                        new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED,
                                new Binding("$node.a.output"))),
                List.of(new WorkflowEdgeSpec("a", "done", new AlwaysCondition(), null)),
                null, "a", null);

        assertThatThrownBy(() -> new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), new CrashBeforeStore(store, "commitNode", "a"))
                .run(spec, "run-first", null)).hasMessage("simulated crash");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), store).run(spec, "run-first", null);

        assertThat(outcome.completed()).isTrue();
        assertThat(aRuns).hasValue(2); // attempt 1 pre-crash + INTERRUPTED re-attempt
        assertContiguousJournalWithSingleTerminal(store.journal("run-first"));
    }

    // ---------------------------------------------------------------------
    // Silent replay of committed routing (no in-flight attempt)
    // ---------------------------------------------------------------------

    @Test
    void silentRouteResumesAfterCrashBetweenNodeCommitAndNextDispatch() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = twoTaskSpec();

        // dies before b's dispatch ever commits: node a is committed, nothing in flight
        assertThatThrownBy(() -> new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), new CrashBeforeStore(store, "commitDispatch", "b"))
                .run(spec, "run-silent", null)).hasMessage("simulated crash");

        CheckpointStore.RunState midCrash = store.openRun("run-silent",
                WorkflowInterpreter.specRef(spec), CanonicalSpecHash.of(spec));
        assertThat(midCrash.inFlight()).isEmpty();
        assertThat(midCrash.committedNodes()).extracting(CheckpointStore.NodeCheckpoint::nodeId)
                .containsExactly("a");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), store).run(spec, "run-silent", null);

        assertThat(outcome.completed()).isTrue();
        assertThat(aRuns).hasValue(1); // replayed silently — a's edge re-selected without events
        assertThat(bRuns).hasValue(1); // the dispatch boundary commits BEFORE the attempt runs,
                                       // so a crash there means b never executed pre-crash
        List<ObjectNode> journal = store.journal("run-silent");
        assertContiguousJournalWithSingleTerminal(journal);
        // exactly one committed dispatch of b: the lost pre-crash one was never journaled
        assertThat(journal.stream().filter(e -> e.get("eventType").asText().equals("OperationDispatched")
                && e.get("nodeId").asText().equals("b"))).hasSize(1);
    }

    @Test
    void decisionOutcomeReplaysThroughSilentRouteOnResume() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("decision-replay", "1.0.0", null, null),
                null, null, null,
                Map.of("op-route", new OperationDeclaration("java:t.route:v1", null, null, null, null, null),
                        "op-win", new OperationDeclaration("java:t.win:v1", null, null, null, null, null),
                        "op-lose", new OperationDeclaration("java:t.lose:v1", null, null, null, null, null)),
                List.of(new DecisionSpecNode("route", null, null, null, "op-route", null,
                                List.of("win", "lose"), null),
                        new TaskSpecNode("win", null, null, null, "op-win", null, null, null),
                        new TaskSpecNode("lose", null, null, null, "op-lose", null, null, null),
                        new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED, null)),
                List.of(new WorkflowEdgeSpec("route", "win", new DecisionResultCondition("win"), null),
                        new WorkflowEdgeSpec("route", "lose", new DecisionResultCondition("lose"), null),
                        new WorkflowEdgeSpec("win", "done", new AlwaysCondition(), null),
                        new WorkflowEdgeSpec("lose", "done", new AlwaysCondition(), null)),
                null, "route", null);
        AtomicInteger winRuns = new AtomicInteger();
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:t.route:v1", (inv, ctx, in) ->
                        OperationResult.success(Map.of("outcome", "win")))
                .register("java:t.win:v1", (inv, ctx, in) -> {
                    winRuns.incrementAndGet();
                    return OperationResult.success("won");
                })
                .register("java:t.lose:v1", (inv, ctx, in) -> OperationResult.success("lost"));

        // dies before the chosen arm's dispatch commits: the decision checkpoint is
        // committed; resume must re-extract the outcome from the wire-framed output
        assertThatThrownBy(() -> new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), new CrashBeforeStore(store, "commitDispatch", "win"))
                .run(spec, "run-decision", null)).hasMessage("simulated crash");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), store).run(spec, "run-decision", null);

        assertThat(outcome.completed()).isTrue();
        assertThat(winRuns).hasValue(1); // crash preceded the attempt: dispatch commits before work
        List<ObjectNode> journal = store.journal("run-decision");
        assertContiguousJournalWithSingleTerminal(journal);
        // the decision executed exactly once; its arm selection was replayed silently
        assertThat(journal.stream().filter(e -> e.has("nodeId")
                && e.get("nodeId").asText().equals("route")
                && e.get("eventType").asText().equals("OperationDispatched"))).hasSize(1);
    }

    @Test
    void terminateReExecutesAfterCrashDuringTerminalCommit() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = twoTaskSpec();

        assertThatThrownBy(() -> new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), new CrashBeforeStore(store, "completeRun", null))
                .run(spec, "run-term", null)).hasMessage("simulated crash");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), store).run(spec, "run-term", null);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("b-out");
        assertThat(aRuns).hasValue(1);
        assertThat(bRuns).hasValue(1); // b was committed — only the terminate re-executed
        List<ObjectNode> journal = store.journal("run-term");
        assertContiguousJournalWithSingleTerminal(journal);
        // the re-minted terminate events appear exactly once (the lost ones never
        // committed): NodeStarted + result BindingEvaluated + NodeCompleted
        assertThat(journal.stream().filter(e -> e.has("nodeId")
                && e.get("nodeId").asText().equals("done"))).hasSize(3);
    }

    // ---------------------------------------------------------------------
    // §17 resume misfire semantics + INTERRUPTED through the retry gate
    // ---------------------------------------------------------------------

    @Test
    void staleCommittedRetryFiresImmediatelyOnResume() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = flakySpec(3_600_000); // one-hour backoff
        SimpleOperationRegistry registry = flakyRegistry();

        // incarnation 1 crashes right after the retry boundary commits — before sleeping
        assertThatThrownBy(() -> new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), new CrashAfterRetryCommitStore(store)).run(spec, "run-stale", null))
                .hasMessage("simulated crash after retry commit");

        // resume TWO HOURS LATER (offset clock): the committed retry is genuinely stale
        // — it fires immediately instead of waiting another hour
        long start = System.nanoTime();
        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.offset(Clock.systemUTC(), Duration.ofHours(2)), store)
                .run(spec, "run-stale", null);
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

    @Test
    void notYetDueCommittedRetryWaitsOutItsRemainingDelay() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = flakySpec(1_500); // 1.5s backoff
        SimpleOperationRegistry registry = flakyRegistry();

        assertThatThrownBy(() -> new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), new CrashAfterRetryCommitStore(store)).run(spec, "run-due", null))
                .hasMessage("simulated crash after retry commit");

        // resume immediately: the retry is NOT yet due — §17 fire-now applies only to
        // retries whose scheduled time has passed, so the remaining delay is honored
        long start = System.nanoTime();
        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), store).run(spec, "run-due", null);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(outcome.completed()).isTrue();
        assertThat(bRuns).hasValue(2);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(500); // waited the remaining backoff
    }

    private WorkflowSpec flakySpec(long delayMillis) {
        return new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("flaky-retry", "1.0.0", null, null),
                null, null, null,
                Map.of("op-b", new OperationDeclaration("java:t.flaky:v1", null, null, null, null, null)),
                List.of(new TaskSpecNode("b", null, null, null, "op-b", null, null,
                                fixedRetry(2, delayMillis)),
                        new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED,
                                new Binding("$node.b.output"))),
                List.of(new WorkflowEdgeSpec("b", "done", new AlwaysCondition(), null)),
                null, "b", null);
    }

    private SimpleOperationRegistry flakyRegistry() {
        return new SimpleOperationRegistry()
                .register("java:t.flaky:v1", (inv, ctx, in) -> {
                    int attempt = bRuns.incrementAndGet();
                    return attempt == 1
                            ? OperationResult.failure(ErrorEnvelope.of("FLAKY", "first attempt fails", true))
                            : OperationResult.success("recovered");
                });
    }

    @Test
    void interruptedNormalizationRoutesErrorEdgesWhenRetryOnExcludesIt() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        PolicyBundle otherCodesOnly = new PolicyBundle(new RetryPolicySpec(3,
                new BackoffSpec(BackoffSpec.Strategy.FIXED, 1, null, null),
                List.of(new ErrorMatcher("SOMETHING_ELSE"))), null);
        WorkflowSpec spec = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("interrupted-routing", "1.0.0", null, null),
                null, null, null,
                Map.of("op-b", new OperationDeclaration("java:t.b:v1", null, null, null, null, null),
                        "op-recover", new OperationDeclaration("java:t.recover:v1", null, null, null, null, null)),
                List.of(new TaskSpecNode("b", null, null, null, "op-b", null, null, otherCodesOnly),
                        new TaskSpecNode("recovery", null, null, null, "op-recover", null, null, null),
                        new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED,
                                new Binding("$node.recovery.output"))),
                List.of(new WorkflowEdgeSpec("b", "done", new AlwaysCondition(), null),
                        new WorkflowEdgeSpec("b", "recovery",
                                new ErrorCondition(new ErrorMatch("INTERRUPTED", null)), null),
                        new WorkflowEdgeSpec("recovery", "done", new AlwaysCondition(), null)),
                null, "b", null);
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:t.b:v1", (inv, ctx, in) -> {
                    bRuns.incrementAndGet();
                    return OperationResult.success("b-out");
                })
                .register("java:t.recover:v1", (inv, ctx, in) -> OperationResult.success("recovered"));

        assertThatThrownBy(() -> new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), new CrashBeforeStore(store, "commitNode", "b"))
                .run(spec, "run-excl", null)).hasMessage("simulated crash");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), store).run(spec, "run-excl", null);

        // retryOn excludes INTERRUPTED → the gate exhausts without re-dispatch and the
        // failure routes through the declared error edge (§17's own documented shape)
        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("recovered");
        assertThat(bRuns).hasValue(1); // never re-dispatched
        List<String> bTypes = store.journal("run-excl").stream()
                .filter(e -> e.has("nodeId") && e.get("nodeId").asText().equals("b"))
                .map(e -> e.get("eventType").asText()).toList();
        assertThat(bTypes).containsExactly("NodeStarted", "OperationDispatched", "OperationFailed",
                "NodeCompleted", "EdgeSelected");
    }

    // ---------------------------------------------------------------------
    // Graceful shutdown: interruption must not out-destroy a crash
    // ---------------------------------------------------------------------

    @Test
    void interruptedDurableRunStaysResumableInsteadOfAborting() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowSpec spec = flakySpec(400);
        SimpleOperationRegistry registry = flakyRegistry();

        // the retry boundary commits, then the interpreter thread is interrupted
        // during the backoff wait (a graceful shutdown)
        Thread.currentThread().interrupt();
        try {
            assertThatExceptionOfType(WorkflowInterruptedException.class)
                    .isThrownBy(() -> new WorkflowInterpreter(registry, new InMemoryEventSink(),
                            Clock.systemUTC(), store).run(spec, "run-int", null))
                    .withMessageContaining("resumable");
        } finally {
            Thread.interrupted(); // clear the flag for the resume below
        }

        // no terminal state was committed: the run resumes and completes
        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, new InMemoryEventSink(),
                Clock.systemUTC(), store).run(spec, "run-int", null);
        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("recovered");
        assertThat(bRuns).hasValue(2);
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
        assertThatThrownBy(() -> new WorkflowInterpreter(registry(), new InMemoryEventSink(),
                Clock.systemUTC(), new CrashBeforeStore(store, "commitNode", "b"))
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
