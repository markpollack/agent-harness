package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.spec.CanonicalJson;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecReader;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine conformance against the golden event stream in {@code spec/events/} — the
 * fixture every interpreter implementation must reproduce (deterministic projection;
 * comparison is canonical-byte equality, the DD-15 cross-SDK equivalence).
 *
 * <p>Regenerate after a deliberate contract change with
 * {@code ./mvnw -pl workflow-engine -am test -Dtest=GoldenEventStreamTest
 * -Dspec.events.regenerate=true} — any event change is a cross-SDK conformance change
 * (Step 2.5 governance).
 */
class GoldenEventStreamTest {

    private static final String FIXTURE = "/spec/events/golden-pr-review.events.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void goldenExampleProducesTheGoldenEventStream() throws Exception {
        InMemoryEventSink sink = new InMemoryEventSink();
        WorkflowSpec spec = readGoldenSpec();
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:github.fetch_pr_diff:v1",
                        (inv, ctx, in) -> OperationResult.success("diff --git a/Foo.java b/Foo.java"))
                .register("python:review.analyze_diff:v2",
                        (inv, ctx, in) -> OperationResult.success(Map.of("summary", "one refactor")))
                .register("java:review.route:v1",
                        (inv, ctx, in) -> OperationResult.success(Map.of("outcome", "post")))
                .register("typescript:github.post_review:v1",
                        (inv, ctx, in) -> OperationResult.success(Map.of("commentId", "c-123")));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, sink)
                .run(spec, "golden-run-1", Map.of("url", "https://github.com/o/r/pull/1"));
        assertThat(outcome.completed()).isTrue();

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("spec", "fixtures/valid/golden-pr-review.json");
        document.put("workflowRunId", "golden-run-1");
        document.put("events", EventStreamProjection.project(sink.events()));
        String actual = CanonicalJson.canonicalize(MAPPER.writeValueAsString(document));

        if (Boolean.getBoolean("spec.events.regenerate")) {
            Path target = Path.of("..", "spec", "events", "golden-pr-review.events.json");
            Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(MAPPER.readTree(actual)) + "\n");
            return;
        }

        try (InputStream fixture = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(fixture)
                    .as("golden stream fixture %s (regenerate with -Dspec.events.regenerate=true)", FIXTURE)
                    .isNotNull();
            String expected = CanonicalJson.canonicalize(
                    new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(actual).isEqualTo(expected);
        }
    }

    private static WorkflowSpec readGoldenSpec() {
        InputStream json = GoldenEventStreamTest.class
                .getResourceAsStream("/spec/fixtures/valid/golden-pr-review.json");
        assertThat(json).isNotNull();
        return new DefaultWorkflowSpecReader().read(json);
    }
}
