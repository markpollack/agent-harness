package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * A declared operation, keyed by alias in the top-level {@code operations} map and
 * referenced from task/decision nodes. {@link #ref()} is the stable capability
 * identifier resolved through the {@code OperationRegistry} (DD-3); its
 * {@code <runtime>:} prefix is descriptive metadata, never dispatch semantics (DD-12).
 */
public record OperationDeclaration(
        String ref,
        String inputSchemaRef,
        JsonNode inputSchema,
        JsonNode outputSchema,
        ExecutionSpec execution,
        PolicyBundle defaultPolicies) {

    public OperationDeclaration {
        Objects.requireNonNull(ref, "ref");
        inputSchema = inputSchema == null ? null : inputSchema.deepCopy();
        outputSchema = outputSchema == null ? null : outputSchema.deepCopy();
    }
}
