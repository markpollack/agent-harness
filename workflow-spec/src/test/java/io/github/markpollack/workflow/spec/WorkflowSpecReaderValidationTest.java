package io.github.markpollack.workflow.spec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Schema-phase rejection through the reader, with the stable SCHEMA_INVALID code. */
class WorkflowSpecReaderValidationTest {

    private final WorkflowSpecReader reader = new DefaultWorkflowSpecReader();

    private WorkflowSpecValidationException readInvalid(String doc) {
        var thrown = assertThatExceptionOfType(WorkflowSpecValidationException.class)
                .isThrownBy(() -> reader.read(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8))));
        return thrown.actual();
    }

    @Test
    void unknownNodeKindIsRejected() {
        var ex = readInvalid("""
                {
                  "apiVersion": "workflow/v2alpha", "kind": "Workflow",
                  "metadata": {"name": "bad"},
                  "operations": {"op": {"ref": "java:x:v1"}},
                  "nodes": [{"id": "g", "kind": "gateway", "operation": "op"}],
                  "edges": [], "entrypoint": "g"
                }
                """);
        assertThat(ex.errors()).isNotEmpty()
                .allSatisfy(e -> assertThat(e.code()).isEqualTo(DefaultWorkflowSpecReader.SCHEMA_INVALID));
    }

    @Test
    void typoedFieldNameIsRejectedNotSwallowed() {
        // DD-19: additionalProperties:false means "retires" is an error, not a no-op.
        var ex = readInvalid("""
                {
                  "apiVersion": "workflow/v2alpha", "kind": "Workflow",
                  "metadata": {"name": "bad"},
                  "operations": {"op": {"ref": "java:x:v1"}},
                  "nodes": [{"id": "t", "kind": "task", "operation": "op"}],
                  "edges": [], "entrypoint": "t",
                  "policies": {"retires": {"maxAttempts": 3}}
                }
                """);
        assertThat(ex.errors()).anySatisfy(e -> assertThat(e.message()).contains("retires"));
    }

    @Test
    void malformedBindingSourceIsRejected() {
        var ex = readInvalid("""
                {
                  "apiVersion": "workflow/v2alpha", "kind": "Workflow",
                  "metadata": {"name": "bad"},
                  "operations": {"op": {"ref": "java:x:v1"}},
                  "nodes": [{"id": "t", "kind": "task", "operation": "op",
                             "input": {"x": {"from": "$inptu.url"}}}],
                  "edges": [], "entrypoint": "t"
                }
                """);
        assertThat(ex.errors()).isNotEmpty();
    }

    @Test
    void unparseableJsonIsRejectedWithSchemaInvalid() {
        var ex = readInvalid("{ not json");
        assertThat(ex.errors()).hasSize(1)
                .first()
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(DefaultWorkflowSpecReader.SCHEMA_INVALID);
                    assertThat(e.message()).contains("not parseable");
                });
    }

    @Test
    void allSchemaErrorsAreCollectedNotJustTheFirst() {
        var ex = readInvalid("""
                {
                  "apiVersion": "workflow/v2alpha", "kind": "Workflow",
                  "metadata": {"name": "bad"},
                  "operations": {},
                  "nodes": [{"id": "d", "kind": "decision", "operation": "op"}],
                  "edges": [], "entrypoint": "d"
                }
                """);
        // empty operations map + decision without outcomes = at least two distinct errors
        assertThat(ex.errors()).hasSizeGreaterThanOrEqualTo(2);
    }
}
