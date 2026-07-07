package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Default reader: parse → JSON Schema validation (phase one) → bind to the sealed model.
 *
 * <p>Phase two (the semantic validator, stable per-rule error codes) is wired here in
 * roadmap Step 1.4; until then this reader guarantees wire-shape validity only.
 * Schema-phase failures carry the stable code {@link #SCHEMA_INVALID}.
 */
public final class DefaultWorkflowSpecReader implements WorkflowSpecReader {

    /** Stable error code pinned by the fixture corpus for wire-shape rejections. */
    public static final String SCHEMA_INVALID = "SCHEMA_INVALID";

    private static final String SCHEMA_RESOURCE = "/spec/workflow-v2alpha.schema.json";

    private final JsonSchema schema;

    public DefaultWorkflowSpecReader() {
        try (InputStream schemaStream = DefaultWorkflowSpecReader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schemaStream == null) {
                throw new IllegalStateException("normative schema not on classpath: " + SCHEMA_RESOURCE);
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            this.schema = factory.getSchema(schemaStream);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load normative schema", e);
        }
    }

    @Override
    public WorkflowSpec read(InputStream json) {
        JsonNode tree;
        try {
            tree = WorkflowSpecJson.mapper().readTree(json);
        } catch (IOException e) {
            throw new WorkflowSpecValidationException(List.of(
                    new ValidationError(SCHEMA_INVALID, "$", "not parseable as JSON: " + e.getMessage())));
        }

        Set<ValidationMessage> messages = schema.validate(tree);
        if (!messages.isEmpty()) {
            List<ValidationError> errors = new ArrayList<>(messages.size());
            for (ValidationMessage message : messages) {
                errors.add(new ValidationError(
                        SCHEMA_INVALID,
                        String.valueOf(message.getInstanceLocation()),
                        message.getMessage()));
            }
            throw new WorkflowSpecValidationException(List.copyOf(errors));
        }

        try {
            return WorkflowSpecJson.mapper().treeToValue(tree, WorkflowSpec.class);
        } catch (IOException | IllegalArgumentException e) {
            // A schema-valid document that fails model binding is a reader defect, not
            // an input defect - surface it loudly rather than as a validation error.
            throw new IllegalStateException("schema-valid document failed model binding", e);
        }
    }
}
