package io.github.markpollack.workflow.engine;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.github.markpollack.workflow.spec.AlwaysCondition;
import io.github.markpollack.workflow.spec.Binding;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecReader;
import io.github.markpollack.workflow.spec.OperationDeclaration;
import io.github.markpollack.workflow.spec.PolicyBundle;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.TerminateSpecNode;
import io.github.markpollack.workflow.spec.TerminateStatus;
import io.github.markpollack.workflow.spec.TimeoutPolicySpec;
import io.github.markpollack.workflow.spec.WorkflowEdgeSpec;
import io.github.markpollack.workflow.spec.WorkflowMetadata;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every envelope the interpreter emits — on every path, including those without a
 * golden stream (cancelled/aborted, timeout, unknown operation, outputs-binding
 * failure) — must validate against the frozen wire schema
 * {@code spec/events/workflow-event.schema.json}. Golden streams compare projections
 * (which strip timestamps and run identity); this test closes the gap on the raw
 * envelope, so a nonconforming excluded field or an unpinned path cannot ship
 * silently.
 */
class LiveEventEnvelopeConformanceTest {

    private static final JsonSchema EVENT_SCHEMA;

    static {
        InputStream stream = LiveEventEnvelopeConformanceTest.class
                .getResourceAsStream("/spec/events/workflow-event.schema.json");
        assertThat(stream).isNotNull();
        EVENT_SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(stream);
    }

    private record Scenario(String name, Supplier<List<WorkflowEvent>> run) {
    }

    @TestFactory
    Stream<DynamicTest> everyLiveEnvelopeValidatesAgainstTheFrozenSchema() {
        List<Scenario> scenarios = List.of(
                new Scenario("golden success", () -> runGolden("post",
                        Map.of("url", "https://github.com/o/r/pull/1"))),
                new Scenario("golden fail path", () -> runGolden("fail",
                        Map.of("url", "https://github.com/o/r/pull/1"))),
                new Scenario("binding failure", () -> runGolden("post", Map.of())),
                new Scenario("retry + error edge", () -> runFixture(
                        "/spec/fixtures/valid/error-edge-routing.json",
                        new SimpleOperationRegistry().register("java:test.work:v1", (inv, ctx, in) ->
                                "call_api".equals(inv.nodeId())
                                        ? OperationResult.failure(ErrorEnvelope.of("RATE_LIMIT", "x", true))
                                        : OperationResult.success("recovered")))),
                new Scenario("cancelled", () -> runMinimal(
                        (inv, ctx, in) -> OperationResult.cancelled("user_stop"))),
                new Scenario("aborted result", () -> runMinimal(
                        (inv, ctx, in) -> OperationResult.aborted("invariant"))),
                new Scenario("unknown operation", () -> runFixture(
                        "/spec/fixtures/valid/minimal-one-task.json", new SimpleOperationRegistry())),
                new Scenario("timeout", LiveEventEnvelopeConformanceTest::runTimeout),
                new Scenario("outputs binding failure", LiveEventEnvelopeConformanceTest::runOutputsFailure));

        return scenarios.stream().map(scenario -> DynamicTest.dynamicTest(scenario.name(), () -> {
            List<WorkflowEvent> events = scenario.run().get();
            assertThat(events).isNotEmpty();
            for (WorkflowEvent event : events) {
                assertThat(EVENT_SCHEMA.validate(WorkflowEventCodec.toJson(event)))
                        .as("%s: event %d (%s)", scenario.name(), event.sequence(), event.eventType())
                        .isEmpty();
            }
        }));
    }

    private static List<WorkflowEvent> runGolden(String outcome, Object input) {
        return runFixture("/spec/fixtures/valid/golden-pr-review.json",
                new SimpleOperationRegistry()
                        .register("java:github.fetch_pr_diff:v1",
                                (inv, ctx, in) -> OperationResult.success("diff"))
                        .register("python:review.analyze_diff:v2",
                                (inv, ctx, in) -> OperationResult.success(Map.of("summary", "s"),
                                        OperationUsage.of(10L, 0.01)))
                        .register("java:review.route:v1",
                                (inv, ctx, in) -> OperationResult.success(Map.of("outcome", outcome)))
                        .register("typescript:github.post_review:v1",
                                (inv, ctx, in) -> OperationResult.success(Map.of("commentId", "c"))),
                input);
    }

    private static List<WorkflowEvent> runFixture(String resource, SimpleOperationRegistry registry) {
        return runFixture(resource, registry, null);
    }

    private static List<WorkflowEvent> runFixture(String resource, SimpleOperationRegistry registry,
            Object input) {
        InputStream json = LiveEventEnvelopeConformanceTest.class.getResourceAsStream(resource);
        assertThat(json).isNotNull();
        WorkflowSpec spec = new DefaultWorkflowSpecReader().read(json);
        InMemoryEventSink sink = new InMemoryEventSink();
        new WorkflowInterpreter(registry, sink).run(spec, "live-run", input);
        return sink.events();
    }

    private static List<WorkflowEvent> runMinimal(OperationHandler handler) {
        return runFixture("/spec/fixtures/valid/minimal-one-task.json",
                new SimpleOperationRegistry().register("java:test.work:v1", handler));
    }

    private static List<WorkflowEvent> runTimeout() {
        WorkflowSpec spec = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("timeout-flow", "1.0.0", null, null),
                null, null, null,
                Map.of("slow", new OperationDeclaration("java:test.slow:v1", null, null, null, null, null)),
                List.of(new TaskSpecNode("do", null, null, null, "slow", null, null,
                                new PolicyBundle(null, new TimeoutPolicySpec(50))),
                        new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED, null)),
                List.of(new WorkflowEdgeSpec("do", "done", new AlwaysCondition(), null)),
                null, "do", null);
        InMemoryEventSink sink = new InMemoryEventSink();
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.slow:v1", (inv, ctx, in) -> {
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return OperationResult.success("late");
                });
        new WorkflowInterpreter(registry, sink).run(spec, "live-run", null);
        return sink.events();
    }

    private static List<WorkflowEvent> runOutputsFailure() {
        WorkflowSpec spec = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("outputs-flow", "1.0.0", null, null),
                null, null, null,
                Map.of("work", new OperationDeclaration("java:test.work:v1", null, null, null, null, null)),
                List.of(new TaskSpecNode("do", null, null, null, "work", null, null, null),
                        new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED, null)),
                List.of(new WorkflowEdgeSpec("do", "done", new AlwaysCondition(), null)),
                null, "do",
                Map.of("result", new Binding("$context.never-written")));
        InMemoryEventSink sink = new InMemoryEventSink();
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) -> OperationResult.success("out"));
        new WorkflowInterpreter(registry, sink).run(spec, "live-run", null);
        return sink.events();
    }
}
