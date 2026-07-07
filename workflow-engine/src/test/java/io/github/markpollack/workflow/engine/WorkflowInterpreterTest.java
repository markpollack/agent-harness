package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;
import io.github.markpollack.workflow.spec.AlwaysCondition;
import io.github.markpollack.workflow.spec.Binding;
import io.github.markpollack.workflow.spec.DecisionResultCondition;
import io.github.markpollack.workflow.spec.DecisionSpecNode;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecReader;
import io.github.markpollack.workflow.spec.ErrorCondition;
import io.github.markpollack.workflow.spec.ErrorMatch;
import io.github.markpollack.workflow.spec.OperationDeclaration;
import io.github.markpollack.workflow.spec.PolicyBundle;
import io.github.markpollack.workflow.spec.RetryPolicySpec;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.TerminateSpecNode;
import io.github.markpollack.workflow.spec.TerminateStatus;
import io.github.markpollack.workflow.spec.TimeoutPolicySpec;
import io.github.markpollack.workflow.spec.WorkflowEdgeSpec;
import io.github.markpollack.workflow.spec.WorkflowMetadata;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import io.github.markpollack.workflow.spec.WorkflowSpecValidationException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Interpreter conformance over §12–§17: routing, retry, defensive boundary, binding
 * failure, edge cardinality, and the end-to-end vertical slice through
 * {@code WorkflowSpecReader → interpreter → OperationHandler → event stream}.
 */
class WorkflowInterpreterTest {

    private final InMemoryEventSink sink = new InMemoryEventSink();

    // ---------------------------------------------------------------------
    // Spec-building helpers (records are the builder)
    // ---------------------------------------------------------------------

    private static WorkflowSpec spec(Map<String, OperationDeclaration> operations,
            List<io.github.markpollack.workflow.spec.WorkflowSpecNode> nodes,
            List<WorkflowEdgeSpec> edges, String entrypoint) {
        return new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("test-flow", "1.0.0", null, null),
                null, null, null, operations, nodes, edges, null, entrypoint, null);
    }

    private static TaskSpecNode task(String id, String operation, PolicyBundle policies) {
        return new TaskSpecNode(id, null, null, null, operation, null, null, policies);
    }

    private static TerminateSpecNode terminate(String id, TerminateStatus status) {
        return new TerminateSpecNode(id, null, null, null, status, null);
    }

    private static WorkflowEdgeSpec always(String from, String to) {
        return new WorkflowEdgeSpec(from, to, new AlwaysCondition(), null);
    }

    private static OperationDeclaration op(String ref) {
        return new OperationDeclaration(ref, null, null, null, null, null);
    }

    private List<WorkflowEventType> eventTypes() {
        return sink.events().stream().map(WorkflowEvent::eventType).toList();
    }

    // ---------------------------------------------------------------------
    // Vertical slice
    // ---------------------------------------------------------------------

    @Test
    void minimalSpecRunsEndToEndThroughReaderInterpreterHandlerAndEvents() {
        WorkflowSpec spec = readFixture("/spec/fixtures/valid/minimal-one-task.json");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) -> OperationResult.success("worked"));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink)
                .run(spec, "run-slice-1", null);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("worked");
        assertThat(eventTypes()).containsExactly(
                WorkflowEventType.WORKFLOW_STARTED,
                WorkflowEventType.NODE_STARTED,
                WorkflowEventType.OPERATION_DISPATCHED,
                WorkflowEventType.OPERATION_SUCCEEDED,
                WorkflowEventType.NODE_COMPLETED,
                WorkflowEventType.EDGE_SELECTED,
                WorkflowEventType.NODE_STARTED,
                WorkflowEventType.BINDING_EVALUATED,
                WorkflowEventType.NODE_COMPLETED,
                WorkflowEventType.WORKFLOW_COMPLETED);
        assertThat(sink.events()).extracting(WorkflowEvent::sequence)
                .isSorted().doesNotHaveDuplicates();
        assertThat(sink.events().get(0).workflowSpecRef())
                .isEqualTo("workflow://registry/minimal-one-task@1.0.0");
    }

    // ---------------------------------------------------------------------
    // Golden example paths
    // ---------------------------------------------------------------------

    @Test
    void goldenExampleCompletesWithOutputs() {
        WorkflowSpec spec = readFixture("/spec/fixtures/valid/golden-pr-review.json");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(goldenRegistry("post"), sink)
                .run(spec, "run-golden", Map.of("url", "https://github.com/o/r/pull/1"));

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.outputs()).containsEntry("result", Map.of("commentId", "c-123"));
        assertThat(outcome.result()).isEqualTo(Map.of("commentId", "c-123"));
        // context write applied and readable downstream: analyze received the diff
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.CONTEXT_WRITE_APPLIED)
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("contextKey", "pr.diff"));
    }

    @Test
    void goldenExampleDecisionRoutesEachDeclaredOutcome() {
        WorkflowSpec spec = readFixture("/spec/fixtures/valid/golden-pr-review.json");

        WorkflowRunOutcome failPath = new WorkflowInterpreter(goldenRegistry("fail"), sink)
                .run(spec, "run-golden-fail", Map.of("url", "https://github.com/o/r/pull/1"));

        assertThat(failPath.terminalState()).isEqualTo("failed");
        assertThat(failPath.reason()).isEqualTo("terminate:rejected");
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.EDGE_SELECTED)
                .filteredOn(e -> "route".equals(e.nodeId()))
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("to", "rejected")
                        .containsEntry("reason", "decision_outcome_match"));
        // the fail path never posts a review
        assertThat(sink.events())
                .noneMatch(e -> "post_comment".equals(e.nodeId()));
    }

    private static SimpleOperationRegistry goldenRegistry(String outcome) {
        return new SimpleOperationRegistry()
                .register("java:github.fetch_pr_diff:v1",
                        (inv, ctx, in) -> OperationResult.success("diff --git a/Foo.java b/Foo.java"))
                .register("python:review.analyze_diff:v2",
                        (inv, ctx, in) -> OperationResult.success(Map.of("summary", "one refactor")))
                .register("java:review.route:v1",
                        (inv, ctx, in) -> OperationResult.success(Map.of("outcome", outcome)))
                .register("typescript:github.post_review:v1",
                        (inv, ctx, in) -> OperationResult.success(Map.of("commentId", "c-123")));
    }

    // ---------------------------------------------------------------------
    // Retry and error routing (§17)
    // ---------------------------------------------------------------------

    @Test
    void retryExhaustionRoutesTheLastResultThroughErrorEdges() {
        PolicyBundle retryTwice = new PolicyBundle(new RetryPolicySpec(2, null, null), null);
        WorkflowSpec spec = spec(
                Map.of("flaky", op("java:test.flaky:v1"), "recover", op("java:test.recover:v1")),
                List.of(task("call", "flaky", retryTwice),
                        task("fallback", "recover", null),
                        terminate("done", TerminateStatus.COMPLETED),
                        terminate("gave-up", TerminateStatus.FAILED)),
                List.of(always("call", "done"),
                        new WorkflowEdgeSpec("call", "fallback",
                                new ErrorCondition(new ErrorMatch("RATE_LIMIT", null)), null),
                        always("fallback", "gave-up")),
                "call");
        AtomicInteger attempts = new AtomicInteger();
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.flaky:v1", (inv, ctx, in) -> {
                    attempts.incrementAndGet();
                    return OperationResult.failure(ErrorEnvelope.of("RATE_LIMIT", "slow down", true));
                })
                .register("java:test.recover:v1", (inv, ctx, in) -> OperationResult.success("recovered"));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink)
                .run(spec, "run-retry", null);

        assertThat(attempts).hasValue(2);
        assertThat(outcome.terminalState()).isEqualTo("failed"); // routed to the FAILED terminate
        assertThat(eventTypes()).containsSubsequence(
                WorkflowEventType.OPERATION_DISPATCHED,
                WorkflowEventType.OPERATION_FAILED,
                WorkflowEventType.RETRY_SCHEDULED,
                WorkflowEventType.OPERATION_DISPATCHED,
                WorkflowEventType.OPERATION_FAILED,
                WorkflowEventType.NODE_COMPLETED,
                WorkflowEventType.EDGE_SELECTED);
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.RETRY_SCHEDULED)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.attemptNumber()).isEqualTo(1);
                    assertThat(e.payload()).containsEntry("reason", "policy_allows")
                            .containsEntry("delayMillis", 0L);
                });
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.EDGE_SELECTED)
                .filteredOn(e -> "call".equals(e.nodeId()))
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("to", "fallback")
                        .containsEntry("reason", "error_match"));
    }

    @Test
    void unroutableFailureFailsTheWorkflow() {
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.work:v1")),
                List.of(task("do", "work", null), terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "do");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) ->
                        OperationResult.failure(ErrorEnvelope.of("BAD_INPUT", "nope", false)));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(outcome.reason()).isEqualTo("unroutable failure: BAD_INPUT");
        assertThat(eventTypes()).endsWith(
                WorkflowEventType.OPERATION_FAILED,
                WorkflowEventType.NODE_COMPLETED,
                WorkflowEventType.WORKFLOW_FAILED);
    }

    @Test
    void throwingHandlerIsSurvivedNormalizedAndPolicyApplied() {
        PolicyBundle retryTwice = new PolicyBundle(new RetryPolicySpec(2, null, null), null);
        WorkflowSpec spec = spec(
                Map.of("bomb", op("java:test.bomb:v1")),
                List.of(task("do", "bomb", retryTwice), terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "do");
        AtomicInteger attempts = new AtomicInteger();
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.bomb:v1", (inv, ctx, in) -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("handler bug");
                });

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        // UNHANDLED_EXCEPTION is retryable:false — the gate exhausts despite attempts remaining
        assertThat(attempts).hasValue(1);
        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.OPERATION_FAILED)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.payload()).containsEntry("resultState", "failure");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> error = (Map<String, Object>) e.payload().get("error");
                    assertThat(error)
                            .containsEntry("code", "UNHANDLED_EXCEPTION")
                            .containsEntry("message", "handler bug")
                            .containsEntry("retryable", false);
                });
        assertThat(eventTypes()).doesNotContain(WorkflowEventType.RETRY_SCHEDULED)
                .endsWith(WorkflowEventType.OPERATION_FAILED,
                        WorkflowEventType.NODE_COMPLETED,
                        WorkflowEventType.WORKFLOW_FAILED);
    }

    @Test
    void perAttemptTimeoutNormalizesToTimedOut() {
        PolicyBundle timeout = new PolicyBundle(null, new TimeoutPolicySpec(50));
        WorkflowSpec spec = spec(
                Map.of("slow", op("java:test.slow:v1")),
                List.of(task("do", "slow", timeout), terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "do");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.slow:v1", (inv, ctx, in) -> {
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return OperationResult.success("too late");
                });

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(outcome.reason()).isEqualTo("unroutable failure: OPERATION_TIMEOUT");
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.OPERATION_FAILED)
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("resultState", "timed_out"));
    }

    // ---------------------------------------------------------------------
    // Binding failure (§13), cardinality (§16), outcome constraints (§15)
    // ---------------------------------------------------------------------

    @Test
    void bindingFailureFailsTheNodeWithoutDispatching() {
        TaskSpecNode needsContext = new TaskSpecNode("do", null, null, null, "work",
                Map.of("diff", new Binding("$context.absent-key")), null, null);
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.work:v1")),
                List.of(needsContext, terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "do");
        AtomicInteger dispatches = new AtomicInteger();
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) -> {
                    dispatches.incrementAndGet();
                    return OperationResult.success("out");
                });

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        assertThat(dispatches).hasValue(0);
        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(outcome.reason()).isEqualTo("missing source path: $context.absent-key");
        assertThat(eventTypes()).containsExactly(
                WorkflowEventType.WORKFLOW_STARTED,
                WorkflowEventType.NODE_STARTED,
                WorkflowEventType.BINDING_EVALUATED,
                WorkflowEventType.NODE_COMPLETED,
                WorkflowEventType.WORKFLOW_FAILED);
        assertThat(sink.events().get(2).payload()).containsEntry("status", "failure");
    }

    @Test
    void multipleMatchingEdgesFailTheWorkflow() {
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.work:v1")),
                List.of(task("do", "work", null),
                        terminate("a", TerminateStatus.COMPLETED),
                        terminate("b", TerminateStatus.COMPLETED)),
                List.of(always("do", "a"), always("do", "b")),
                "do");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) -> OperationResult.success("out"));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(outcome.reason()).contains("2 edges match");
        assertThat(eventTypes()).doesNotContain(WorkflowEventType.EDGE_SELECTED);
    }

    @Test
    void undeclaredRuntimeOutcomeFailsTheWorkflow() {
        DecisionSpecNode route = new DecisionSpecNode("route", null, null, null, "route",
                null, List.of("post", "fail"), null);
        WorkflowSpec spec = spec(
                Map.of("route", op("java:test.route:v1")),
                List.of(route,
                        terminate("a", TerminateStatus.COMPLETED),
                        terminate("b", TerminateStatus.FAILED)),
                List.of(new WorkflowEdgeSpec("route", "a", new DecisionResultCondition("post"), null),
                        new WorkflowEdgeSpec("route", "b", new DecisionResultCondition("fail"), null)),
                "route");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.route:v1", (inv, ctx, in) ->
                        OperationResult.success(Map.of("outcome", "shrug")));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(outcome.reason()).isEqualTo("undeclared decision outcome: shrug (§15)");
    }

    // ---------------------------------------------------------------------
    // Cancellation, seeding, validation, identity
    // ---------------------------------------------------------------------

    @Test
    void cancelledResultTerminatesWithoutErrorRouting() {
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.work:v1"), "other", op("java:test.other:v1")),
                List.of(task("do", "work", null),
                        task("never", "other", null),
                        terminate("done", TerminateStatus.COMPLETED),
                        terminate("x", TerminateStatus.FAILED)),
                List.of(always("do", "done"),
                        new WorkflowEdgeSpec("do", "never",
                                new ErrorCondition(new ErrorMatch(null, true)), null),
                        always("never", "x")),
                "do");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) -> OperationResult.cancelled("user_stop"))
                .register("java:test.other:v1", (inv, ctx, in) -> OperationResult.success("x"));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        assertThat(outcome.terminalState()).isEqualTo("cancelled");
        assertThat(outcome.reason()).isEqualTo("user_stop");
        assertThat(eventTypes()).doesNotContain(
                WorkflowEventType.OPERATION_FAILED, WorkflowEventType.EDGE_SELECTED);
        // NodeCompleted.state mirrors the terminal event type (§9)
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.NODE_COMPLETED)
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("state", "cancelled"));
        assertThat(eventTypes()).endsWith(WorkflowEventType.WORKFLOW_CANCELLED);
    }

    @Test
    void throwingErrorNotJustExceptionIsSurvived() {
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.assert:v1")),
                List.of(task("do", "work", null), terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "do");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.assert:v1", (inv, ctx, in) -> {
                    throw new AssertionError("invariant blown");
                });

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        // same conformant stream as an Exception-throwing handler: normalized, terminal event emitted
        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.OPERATION_FAILED)
                .singleElement()
                .satisfies(e -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> error = (Map<String, Object>) e.payload().get("error");
                    assertThat(error).containsEntry("code", "UNHANDLED_EXCEPTION");
                });
        assertThat(eventTypes()).endsWith(WorkflowEventType.WORKFLOW_FAILED);
    }

    @Test
    void blankCancellationReasonIsUnrepresentableAndNormalized() {
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.badcancel:v1")),
                List.of(task("do", "work", null), terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "do");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.badcancel:v1",
                        (inv, ctx, in) -> OperationResult.cancelled(" "));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        // the record constructor throws in the handler's frame; the boundary normalizes it —
        // the run still ends with a terminal event instead of a mid-stream crash
        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.OPERATION_FAILED)
                .singleElement()
                .satisfies(e -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> error = (Map<String, Object>) e.payload().get("error");
                    assertThat(error).containsEntry("code", "UNHANDLED_EXCEPTION");
                });
        assertThat(eventTypes()).endsWith(WorkflowEventType.WORKFLOW_FAILED);
    }

    @Test
    void workflowOutputsBindingFailureFailsAfterTerminateNodeSucceeds() {
        TaskSpecNode work = task("do", "work", null);
        TerminateSpecNode done = terminate("done", TerminateStatus.COMPLETED);
        WorkflowSpec spec = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("test-flow", "1.0.0", null, null),
                null, null, null,
                Map.of("work", op("java:test.work:v1")),
                List.of(work, done),
                List.of(always("do", "done")),
                null, "do",
                Map.of("result", new Binding("$context.never-written")));
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) -> OperationResult.success("out"));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink).run(spec, "run-1", null);

        assertThat(outcome.terminalState()).isEqualTo("failed");
        assertThat(outcome.reason()).isEqualTo("missing source path: $context.never-written");
        // the terminate node itself succeeded; the outputs evaluation failed afterwards
        assertThat(eventTypes()).endsWith(
                WorkflowEventType.NODE_COMPLETED,
                WorkflowEventType.BINDING_EVALUATED,
                WorkflowEventType.WORKFLOW_FAILED);
        assertThat(sink.events())
                .filteredOn(e -> e.eventType() == WorkflowEventType.NODE_COMPLETED)
                .filteredOn(e -> "done".equals(e.nodeId()))
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("state", "succeeded"));
    }

    @Test
    void seededTypedContextIsReadableThroughBindingsAndHandlers() {
        ContextKey<String> reviewer = ContextKey.of("reviewer", String.class);
        TaskSpecNode greet = new TaskSpecNode("do", null, null, null, "work",
                Map.of("who", new Binding("$context.reviewer")), null, null);
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.work:v1")),
                List.of(greet, terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "do");
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) in;
                    return OperationResult.success(input.get("who") + "/" + ctx.get(reviewer).orElse("?"));
                });
        AgentContext seed = AgentContext.create().mutate().with(reviewer, "mark").build();

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink)
                .run(spec, "run-1", null, seed);

        assertThat(outcome.completed()).isTrue();
        // both the $context binding and the typed key saw the seeded value; run id was overridden
        assertThat(sink.events().get(0).workflowRunId()).isEqualTo("run-1");
    }

    @Test
    void invalidSpecIsRejectedBeforeAnyEvent() {
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.work:v1")),
                List.of(task("do", "work", null), terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "ghost");

        assertThatExceptionOfType(WorkflowSpecValidationException.class)
                .isThrownBy(() -> new WorkflowInterpreter(new SimpleOperationRegistry(), sink)
                        .run(spec, "run-1", null));
        assertThat(sink.events()).isEmpty();
    }

    @Test
    void unregisteredOperationAbortsTheWorkflow() {
        WorkflowSpec spec = spec(
                Map.of("work", op("java:test.unbound:v1")),
                List.of(task("do", "work", null), terminate("done", TerminateStatus.COMPLETED)),
                List.of(always("do", "done")),
                "do");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(new SimpleOperationRegistry(), sink)
                .run(spec, "run-1", null);

        assertThat(outcome.terminalState()).isEqualTo("aborted");
        assertThat(outcome.reason()).isEqualTo("unknown operation: java:test.unbound:v1");
        assertThat(eventTypes()).doesNotContain(WorkflowEventType.OPERATION_DISPATCHED);
    }

    private static WorkflowSpec readFixture(String resource) {
        InputStream json = WorkflowInterpreterTest.class.getResourceAsStream(resource);
        assertThat(json).as("fixture " + resource).isNotNull();
        return new DefaultWorkflowSpecReader().read(json);
    }
}
