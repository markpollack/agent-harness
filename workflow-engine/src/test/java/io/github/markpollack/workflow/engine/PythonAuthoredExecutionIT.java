package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.spec.CanonicalJson;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecReader;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The polyglot proof, end to end (VISION success criterion 6; roadmap Step P2.3):
 * the Python SDK authors and emits the golden workflow LIVE (via {@code uv}), the
 * JVM reads it through {@code WorkflowSpecReader} (both validation phases), the v2
 * interpreter executes it against stub handlers, and the event streams are
 * canonically byte-equal to the golden streams in {@code spec/events/} — for BOTH
 * decision outcomes.
 *
 * <p>The execution-handoff story this test pins: Python authors → canonical JSON →
 * any transport → {@code WorkflowSpecReader.read} → {@code WorkflowInterpreter.run}.
 * The SDK never executes; the engine never authors.
 *
 * <p>Requires {@code uv} on the PATH (skips otherwise — environment-gated like the
 * other assumption-based ITs).
 */
class PythonAuthoredExecutionIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path PYTHON_SDK = Path.of("..", "sdks", "python").toAbsolutePath().normalize();

    private static byte[] pythonEmittedSpec;

    @BeforeAll
    static void emitFromPython() throws Exception {
        assumeTrue(Files.isDirectory(PYTHON_SDK), "python SDK tree not present");
        Process process;
        try {
            process = new ProcessBuilder("uv", "run", "--project", PYTHON_SDK.toString(),
                    "python", PYTHON_SDK.resolve("examples/golden_pr_review.py").toString())
                    .redirectErrorStream(false)
                    .start();
        } catch (Exception ex) {
            assumeTrue(false, "uv not available: " + ex.getMessage());
            return;
        }
        byte[] stdout = process.getInputStream().readAllBytes();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("emitter timed out").isTrue();
        assertThat(process.exitValue()).as("python emitter failed: %s", stderr).isZero();
        pythonEmittedSpec = stdout;
    }

    @Test
    void pythonEmissionIsByteEqualToTheSharedFixture() throws Exception {
        byte[] fixture = Files.readAllBytes(
                Path.of("..", "spec", "fixtures", "valid", "golden-pr-review.json"));
        assertThat(pythonEmittedSpec).isEqualTo(CanonicalJson.canonicalize(fixture));
    }

    @Test
    void pythonAuthoredWorkflowExecutesWithGoldenConformantStreams() throws Exception {
        WorkflowSpec spec = new DefaultWorkflowSpecReader()
                .read(new ByteArrayInputStream(pythonEmittedSpec));

        // the golden workflow is a decision workflow: exercise BOTH outcomes
        verifyAgainstGoldenStream(spec, "golden-pr-review", "golden-run-1", "post", "completed");
        verifyAgainstGoldenStream(spec, "golden-pr-review-fail-path", "golden-run-2", "fail", "failed");
    }

    private void verifyAgainstGoldenStream(WorkflowSpec spec, String stream, String runId,
            String outcome, String terminalState) throws Exception {
        InMemoryEventSink sink = new InMemoryEventSink();
        WorkflowRunOutcome result = new WorkflowInterpreter(registry(outcome), sink)
                .run(spec, runId, Map.of("url", "https://github.com/o/r/pull/1"));
        assertThat(result.terminalState()).isEqualTo(terminalState);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("spec", "fixtures/valid/golden-pr-review.json");
        document.put("workflowRunId", runId);
        document.put("events", EventStreamProjection.project(sink.events()));
        String actual = CanonicalJson.canonicalize(MAPPER.writeValueAsString(document));

        try (InputStream golden = getClass().getResourceAsStream("/spec/events/" + stream + ".events.json")) {
            assertThat(golden).isNotNull();
            String expected = CanonicalJson.canonicalize(
                    new String(golden.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(actual).as("stream %s", stream).isEqualTo(expected);
        }
    }

    /** The documented golden-stream stub handlers (spec/README.md conformance setup). */
    private static SimpleOperationRegistry registry(String outcome) {
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
}
