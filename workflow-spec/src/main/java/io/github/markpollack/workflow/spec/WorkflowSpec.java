package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The v2-alpha WorkflowSpec: a language-neutral, data-only workflow definition
 * (apiVersion {@code workflow/v2alpha}). This model mirrors the normative wire schema
 * {@code spec/workflow-v2alpha.schema.json} 1:1 — the JSON wire format is the Published
 * Language; this class is the JVM conformist rendering of it.
 *
 * <p>Construction enforces only cheap local invariants (required fields present).
 * Cross-language validity is the job of two-phase validation: JSON Schema (wire shape)
 * plus the semantic validator (graph rules) — a {@link WorkflowSpec} obtained through
 * {@link WorkflowSpecReader#read} has passed both.
 *
 * <p>The open sections ({@code types}, {@code constants}, {@code contextSchema}) are
 * carried as {@link JsonNode} verbatim: they hold embedded JSON Schema documents or
 * arbitrary user constants and round-trip losslessly.
 */
public record WorkflowSpec(
        String apiVersion,
        String kind,
        WorkflowMetadata metadata,
        JsonNode types,
        JsonNode constants,
        JsonNode contextSchema,
        Map<String, OperationDeclaration> operations,
        List<WorkflowSpecNode> nodes,
        List<WorkflowEdgeSpec> edges,
        PolicyBundle policies,
        String entrypoint,
        Map<String, Binding> outputs) {

    public static final String API_VERSION = "workflow/v2alpha";
    public static final String KIND = "Workflow";

    public WorkflowSpec {
        Objects.requireNonNull(apiVersion, "apiVersion");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        entrypoint = SpecInvariants.requireNonBlank(entrypoint, "entrypoint");
        if (!API_VERSION.equals(apiVersion)) {
            throw new IllegalArgumentException("unsupported apiVersion: " + apiVersion);
        }
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("unsupported kind: " + kind);
        }
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("operations must not be empty");
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
        types = types == null ? null : types.deepCopy();
        constants = constants == null ? null : constants.deepCopy();
        contextSchema = contextSchema == null ? null : contextSchema.deepCopy();
        operations = Map.copyOf(operations);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        outputs = outputs == null ? null : Map.copyOf(outputs);
    }
}
