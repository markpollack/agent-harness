package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.spec.CanonicalJson;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecReader;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine conformance against the golden event streams in {@code spec/events/} — the
 * fixtures every interpreter implementation must reproduce. Comparison is
 * canonical-byte equality of the deterministic projection (DD-15 cross-SDK
 * equivalence; never compare event trees with {@code JsonNode.equals} — Jackson's
 * IntNode≠LongNode).
 *
 * <p>Regenerate after a deliberate contract change with
 * {@code ./mvnw -pl workflow-engine -am test -Dtest=GoldenEventStreamTest
 * -Dspec.events.regenerate=true -Dsurefire.failIfNoSpecifiedTests=false} — any event
 * change is a cross-SDK conformance change (governance in {@code spec/README.md}).
 */
class GoldenEventStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Scenario(String fixture, String specResource, String runId, Object input,
            SimpleOperationRegistry registry, String expectedTerminalState) {
    }

    private static List<Scenario> scenarios() {
        String goldenSpec = "fixtures/valid/golden-pr-review.json";
        String routingSpec = "fixtures/valid/error-edge-routing.json";
        return List.of(
                new Scenario("golden-pr-review", goldenSpec, "golden-run-1",
                        Map.of("url", "https://github.com/o/r/pull/1"),
                        goldenRegistry("post"), "completed"),
                new Scenario("golden-pr-review-fail-path", goldenSpec, "golden-run-2",
                        Map.of("url", "https://github.com/o/r/pull/1"),
                        goldenRegistry("fail"), "failed"),
                new Scenario("golden-pr-review-binding-failure", goldenSpec, "golden-run-3",
                        Map.of(), goldenRegistry("post"), "failed"),
                new Scenario("error-edge-routing", routingSpec, "golden-run-4", null,
                        routingRegistry("RATE_LIMIT"), "failed"),
                new Scenario("retry-exhaustion-unroutable", routingSpec, "golden-run-5", null,
                        routingRegistry("SCHEMA_DRIFT"), "failed"),
                new Scenario("operation-cancelled", "fixtures/valid/minimal-one-task.json",
                        "golden-run-6", null,
                        new SimpleOperationRegistry().register("java:test.work:v1",
                                (inv, ctx, in) -> OperationResult.cancelled("user_stop")),
                        "cancelled"));
    }

    @TestFactory
    Stream<DynamicTest> goldenStreams() {
        return scenarios().stream().map(scenario ->
                DynamicTest.dynamicTest(scenario.fixture(), () -> verify(scenario)));
    }

    private void verify(Scenario scenario) throws Exception {
        InMemoryEventSink sink = new InMemoryEventSink();
        WorkflowSpec spec = readSpec("/spec/" + scenario.specResource());

        WorkflowRunOutcome outcome = new WorkflowInterpreter(scenario.registry(), sink)
                .run(spec, scenario.runId(), scenario.input());
        assertThat(outcome.terminalState()).isEqualTo(scenario.expectedTerminalState());

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("spec", scenario.specResource());
        document.put("workflowRunId", scenario.runId());
        document.put("events", EventStreamProjection.project(sink.events()));
        String actual = CanonicalJson.canonicalize(MAPPER.writeValueAsString(document));

        if (Boolean.getBoolean("spec.events.regenerate")) {
            Path target = Path.of("..", "spec", "events", scenario.fixture() + ".events.json");
            Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(MAPPER.readTree(actual)) + "\n");
            return;
        }

        String resource = "/spec/events/" + scenario.fixture() + ".events.json";
        try (InputStream fixture = getClass().getResourceAsStream(resource)) {
            assertThat(fixture)
                    .as("golden stream fixture %s (regenerate with -Dspec.events.regenerate=true)", resource)
                    .isNotNull();
            String expected = CanonicalJson.canonicalize(
                    new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(actual).isEqualTo(expected);
        }
    }

    /**
     * Golden-example handlers; the analyze step reports usage — pinning the §9
     * usage/cost payload shape in the golden stream.
     */
    private static SimpleOperationRegistry goldenRegistry(String outcome) {
        return new SimpleOperationRegistry()
                .register("java:github.fetch_pr_diff:v1",
                        (inv, ctx, in) -> OperationResult.success("diff --git a/Foo.java b/Foo.java"))
                .register("python:review.analyze_diff:v2",
                        (inv, ctx, in) -> OperationResult.success(Map.of("summary", "one refactor"),
                                OperationUsage.of(1200L, 0.05)))
                .register("java:review.route:v1",
                        (inv, ctx, in) -> OperationResult.success(Map.of("outcome", outcome)))
                .register("typescript:github.post_review:v1",
                        (inv, ctx, in) -> OperationResult.success(Map.of("commentId", "c-123")));
    }

    /** error-edge-routing handlers: call_api fails with the given code, fallback recovers. */
    private static SimpleOperationRegistry routingRegistry(String errorCode) {
        return new SimpleOperationRegistry()
                .register("java:test.work:v1", (inv, ctx, in) ->
                        "call_api".equals(inv.nodeId())
                                ? OperationResult.failure(
                                        ErrorEnvelope.of(errorCode, "upstream rejected the call", true))
                                : OperationResult.success("recovered"));
    }

    private static WorkflowSpec readSpec(String resource) {
        InputStream json = GoldenEventStreamTest.class.getResourceAsStream(resource);
        assertThat(json).as("spec fixture " + resource).isNotNull();
        return new DefaultWorkflowSpecReader().read(json);
    }
}
