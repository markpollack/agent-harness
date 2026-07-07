package io.github.markpollack.workflow.spec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip laws for the golden example (alpha spec §20):
 * {@code write(read(j))} is canonically byte-equal to {@code canonicalize(j)}, and
 * model → JSON → model preserves equality.
 */
class WorkflowSpecRoundTripTest {

    private static final String GOLDEN = "/spec/fixtures/valid/golden-pr-review.json";

    private final WorkflowSpecReader reader = new DefaultWorkflowSpecReader();
    private final WorkflowSpecWriter writer = new DefaultWorkflowSpecWriter();

    private byte[] goldenBytes() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(GOLDEN)) {
            assertThat(in).as("golden fixture on test classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void goldenExampleDeserializesToExpectedModel() throws IOException {
        WorkflowSpec spec = reader.read(new ByteArrayInputStream(goldenBytes()));

        assertThat(spec.apiVersion()).isEqualTo("workflow/v2alpha");
        assertThat(spec.kind()).isEqualTo("Workflow");
        assertThat(spec.metadata().name()).isEqualTo("pr-review");
        assertThat(spec.metadata().version()).isEqualTo("1.0.0");
        assertThat(spec.operations()).containsOnlyKeys(
                "fetch-pr-diff", "analyze-diff", "route-review", "post-review");
        assertThat(spec.operations().get("analyze-diff").ref()).isEqualTo("python:review.analyze_diff:v2");
        assertThat(spec.nodes()).hasSize(5);
        assertThat(spec.edges()).hasSize(5);
        assertThat(spec.entrypoint()).isEqualTo("fetch_diff");
        assertThat(spec.constants().get("approval_threshold").doubleValue()).isEqualTo(0.8);

        assertThat(spec.nodes().get(0)).isInstanceOfSatisfying(TaskSpecNode.class, task -> {
            assertThat(task.id()).isEqualTo("fetch_diff");
            assertThat(task.operation()).isEqualTo("fetch-pr-diff");
            assertThat(task.input()).containsKey("url");
            assertThat(task.input().get("url").from()).isEqualTo("$input.url");
            assertThat(task.contextWrites()).containsKey("pr.diff");
        });
        assertThat(spec.nodes().get(2)).isInstanceOfSatisfying(DecisionSpecNode.class, decision -> {
            assertThat(decision.id()).isEqualTo("route");
            assertThat(decision.outcomes()).containsExactly("post", "fail");
        });
        assertThat(spec.nodes().get(4)).isInstanceOfSatisfying(TerminateSpecNode.class, terminate -> {
            assertThat(terminate.id()).isEqualTo("done");
            assertThat(terminate.status()).isEqualTo(TerminateStatus.COMPLETED);
            assertThat(terminate.result().from()).isEqualTo("$node.post_comment.output");
        });

        assertThat(spec.edges().get(2).when()).isInstanceOfSatisfying(
                DecisionResultCondition.class, c -> assertThat(c.value()).isEqualTo("post"));
        assertThat(spec.edges().get(0).when()).isInstanceOf(AlwaysCondition.class);
    }

    @Test
    void writeReadEqualsCanonicalizeForGolden() throws IOException {
        byte[] raw = goldenBytes();
        WorkflowSpec spec = reader.read(new ByteArrayInputStream(raw));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(spec, out);

        assertThat(out.toByteArray()).isEqualTo(CanonicalJson.canonicalize(raw));
    }

    @Test
    void modelToJsonToModelPreservesEquality() throws IOException {
        WorkflowSpec first = reader.read(new ByteArrayInputStream(goldenBytes()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(first, out);
        WorkflowSpec second = reader.read(new ByteArrayInputStream(out.toByteArray()));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void canonicalFormIsStableAcrossRepeatedRoundTrips() throws IOException {
        WorkflowSpec spec = reader.read(new ByteArrayInputStream(goldenBytes()));

        ByteArrayOutputStream once = new ByteArrayOutputStream();
        writer.write(spec, once);
        WorkflowSpec again = reader.read(new ByteArrayInputStream(once.toByteArray()));
        ByteArrayOutputStream twice = new ByteArrayOutputStream();
        writer.write(again, twice);

        assertThat(twice.toByteArray()).isEqualTo(once.toByteArray());
    }

    @Test
    void keyOrderDoesNotAffectCanonicalForm() throws IOException {
        String reordered = """
                {
                  "kind": "Workflow",
                  "entrypoint": "only",
                  "edges": [],
                  "nodes": [
                    {"operation": "op", "kind": "task", "id": "only"}
                  ],
                  "operations": {"op": {"ref": "java:test.op:v1"}},
                  "metadata": {"name": "reordered"},
                  "apiVersion": "workflow/v2alpha"
                }
                """;
        WorkflowSpec spec = reader.read(new ByteArrayInputStream(reordered.getBytes(StandardCharsets.UTF_8)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(spec, out);

        assertThat(out.toByteArray())
                .isEqualTo(CanonicalJson.canonicalize(reordered.getBytes(StandardCharsets.UTF_8)));
        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("{\"apiVersion\":");
    }

    @Test
    void numericLiteralsNormalizePerRfc8785() {
        // JCS number formatting is ECMAScript: 1.0 -> 1, 1e2 -> 100, 0.8 stays 0.8.
        // This is the numeric-scale round-trip trap from the Step 1.0 testing-KB review.
        String doc = """
                {
                  "apiVersion": "workflow/v2alpha",
                  "kind": "Workflow",
                  "metadata": {"name": "numbers"},
                  "constants": {"a": 1.0, "b": 1e2, "c": 0.8},
                  "operations": {"op": {"ref": "java:test.op:v1"}},
                  "nodes": [{"id": "only", "kind": "task", "operation": "op"}],
                  "edges": [],
                  "entrypoint": "only"
                }
                """;
        WorkflowSpec spec = reader.read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(spec, out);
        String canonical = out.toString(StandardCharsets.UTF_8);

        assertThat(canonical).contains("\"a\":1,").contains("\"b\":100,").contains("\"c\":0.8");
        assertThat(out.toByteArray()).isEqualTo(CanonicalJson.canonicalize(doc.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void annotationsRoundTripLosslessly() {
        // DD-19: annotations on metadata and nodes MUST be preserved by every SDK.
        String doc = """
                {
                  "apiVersion": "workflow/v2alpha",
                  "kind": "Workflow",
                  "metadata": {"name": "annotated", "annotations": {"editor.example.io/theme": "dark"}},
                  "operations": {"op": {"ref": "java:test.op:v1"}},
                  "nodes": [{"id": "only", "kind": "task", "operation": "op",
                             "annotations": {"editor.example.io/position": "{\\"x\\":120,\\"y\\":80}"}}],
                  "edges": [],
                  "entrypoint": "only"
                }
                """;
        WorkflowSpec spec = reader.read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)));

        assertThat(spec.metadata().annotations()).containsEntry("editor.example.io/theme", "dark");
        assertThat(((TaskSpecNode) spec.nodes().get(0)).annotations())
                .containsEntry("editor.example.io/position", "{\"x\":120,\"y\":80}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(spec, out);
        assertThat(out.toByteArray()).isEqualTo(CanonicalJson.canonicalize(doc.getBytes(StandardCharsets.UTF_8)));
    }
}
