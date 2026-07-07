package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * The wire projection of {@link OperationResult} — the envelope workers in any language
 * return and the journal records ({@code spec/operation-result.schema.json} is the
 * normative shape, frozen at Step 2.5). Status-discriminated lowercase forms (§6),
 * no nulls on the wire, cross-language equivalence = canonical byte equality.
 *
 * <p>{@code fromJson} is strict: unknown status, missing required fields, or wrong
 * shapes throw {@link IllegalArgumentException} — a malformed result envelope from a
 * worker is a defect to surface, never data to guess at.
 */
public final class OperationResultCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private OperationResultCodec() {
    }

    public static ObjectNode toJson(OperationResult result) {
        ObjectNode node = WorkflowEventJson.mapper().createObjectNode();
        node.put("status", result.status().wireName());
        switch (result) {
            case OperationResult.Success s -> {
                if (s.output() != null) {
                    node.set("output", WorkflowEventJson.mapper().valueToTree(s.output()));
                }
                putUsage(node, s.usage());
            }
            case OperationResult.Failure f -> {
                node.set("error", WorkflowEventJson.mapper().valueToTree(f.error()));
                putUsage(node, f.usage());
            }
            case OperationResult.TimedOut t -> {
                node.set("error", WorkflowEventJson.mapper().valueToTree(t.error()));
                putUsage(node, t.usage());
            }
            case OperationResult.Cancelled c -> node.put("reason", c.reason());
            case OperationResult.Aborted a -> node.put("reason", a.reason());
        }
        return node;
    }

    public static OperationResult fromJson(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("operation result must be a JSON object");
        }
        String status = requiredText(json, "status");
        return switch (status) {
            case "success" -> OperationResult.success(
                    json.hasNonNull("output") ? toJava(json.get("output")) : null,
                    usage(json));
            case "failure" -> OperationResult.failure(error(json), usage(json));
            case "timed_out" -> OperationResult.timedOut(error(json), usage(json));
            case "cancelled" -> OperationResult.cancelled(requiredText(json, "reason"));
            case "aborted" -> OperationResult.aborted(requiredText(json, "reason"));
            default -> throw new IllegalArgumentException("unknown operation result status: " + status);
        };
    }

    private static ErrorEnvelope error(JsonNode json) {
        JsonNode error = json.get("error");
        if (error == null || !error.isObject()) {
            throw new IllegalArgumentException("failure/timed_out result requires an 'error' object");
        }
        if (!error.has("retryable") || !error.get("retryable").isBoolean()) {
            throw new IllegalArgumentException("error envelope requires boolean 'retryable'");
        }
        return new ErrorEnvelope(
                requiredText(error, "code"),
                error.hasNonNull("message") ? error.get("message").asText() : null,
                error.get("retryable").asBoolean(),
                error.hasNonNull("origin") ? error.get("origin").asText() : null,
                error.hasNonNull("details")
                        ? WorkflowEventJson.mapper().convertValue(error.get("details"), MAP_TYPE)
                        : null);
    }

    private static OperationUsage usage(JsonNode json) {
        JsonNode usage = json.get("usage");
        if (usage == null || usage.isNull()) {
            return null;
        }
        return new OperationUsage(
                usage.hasNonNull("tokens") ? usage.get("tokens").asLong() : null,
                usage.hasNonNull("costUsd") ? usage.get("costUsd").asDouble() : null);
    }

    private static void putUsage(ObjectNode node, OperationUsage usage) {
        if (usage != null) {
            node.set("usage", WorkflowEventJson.mapper().valueToTree(usage));
        }
    }

    private static Object toJava(JsonNode node) {
        return WorkflowEventJson.mapper().convertValue(node, Object.class);
    }

    private static String requiredText(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("operation result requires string '" + field + "'");
        }
        return value.asText();
    }
}
