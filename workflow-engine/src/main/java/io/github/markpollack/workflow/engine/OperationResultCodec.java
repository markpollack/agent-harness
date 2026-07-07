package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The wire projection of {@link OperationResult} — the envelope workers in any language
 * return and the journal records ({@code spec/operation-result.schema.json} is the
 * normative shape, frozen at Step 2.5). Status-discriminated lowercase forms (§6),
 * no nulls on the wire, cross-language equivalence = canonical byte equality.
 *
 * <p>{@code fromJson} is strict — a malformed result envelope from a worker is a
 * defect to surface, never data to guess at: unknown status, missing/mistyped required
 * fields, unknown properties (a typo'd {@code usge} must not be silently dropped),
 * explicit {@code null} output (the wire has no null), and non-integral or negative
 * usage numbers all throw {@link IllegalArgumentException}.
 */
public final class OperationResultCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final Set<String> SUCCESS_KEYS = Set.of("status", "output", "usage");
    private static final Set<String> ERRORED_KEYS = Set.of("status", "error", "usage");
    private static final Set<String> STOPPED_KEYS = Set.of("status", "reason");
    private static final Set<String> ERROR_ENVELOPE_KEYS =
            Set.of("code", "message", "retryable", "origin", "details");
    private static final Set<String> USAGE_KEYS = Set.of("tokens", "costUsd");

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
        String status = requiredText(json, "status", "operation result");
        return switch (status) {
            case "success" -> {
                requireOnlyKeys(json, SUCCESS_KEYS, "success result");
                if (json.has("output") && json.get("output").isNull()) {
                    throw new IllegalArgumentException(
                            "output must not be null — omit it (the wire has no null)");
                }
                yield OperationResult.success(
                        json.has("output") ? toJava(json.get("output")) : null,
                        usage(json));
            }
            case "failure" -> {
                requireOnlyKeys(json, ERRORED_KEYS, "failure result");
                yield OperationResult.failure(error(json), usage(json));
            }
            case "timed_out" -> {
                requireOnlyKeys(json, ERRORED_KEYS, "timed_out result");
                yield OperationResult.timedOut(error(json), usage(json));
            }
            case "cancelled" -> {
                requireOnlyKeys(json, STOPPED_KEYS, "cancelled result");
                yield OperationResult.cancelled(requiredText(json, "reason", "cancelled result"));
            }
            case "aborted" -> {
                requireOnlyKeys(json, STOPPED_KEYS, "aborted result");
                yield OperationResult.aborted(requiredText(json, "reason", "aborted result"));
            }
            default -> throw new IllegalArgumentException("unknown operation result status: " + status);
        };
    }

    private static ErrorEnvelope error(JsonNode json) {
        JsonNode error = json.get("error");
        if (error == null || !error.isObject()) {
            throw new IllegalArgumentException("failure/timed_out result requires an 'error' object");
        }
        requireOnlyKeys(error, ERROR_ENVELOPE_KEYS, "error envelope");
        if (!error.has("retryable") || !error.get("retryable").isBoolean()) {
            throw new IllegalArgumentException("error envelope requires boolean 'retryable'");
        }
        if (error.has("message") && !error.get("message").isTextual()) {
            throw new IllegalArgumentException("error 'message' must be a string");
        }
        if (error.has("details") && !error.get("details").isObject()) {
            throw new IllegalArgumentException("error 'details' must be an object");
        }
        return new ErrorEnvelope(
                requiredText(error, "code", "error envelope"),
                error.has("message") ? error.get("message").asText() : null,
                error.get("retryable").asBoolean(),
                error.has("origin") ? requiredText(error, "origin", "error envelope") : null,
                error.has("details")
                        ? WorkflowEventJson.mapper().convertValue(error.get("details"), MAP_TYPE)
                        : null);
    }

    private static OperationUsage usage(JsonNode json) {
        JsonNode usage = json.get("usage");
        if (usage == null) {
            return null;
        }
        if (!usage.isObject()) {
            throw new IllegalArgumentException("'usage' must be an object");
        }
        requireOnlyKeys(usage, USAGE_KEYS, "usage");
        Long tokens = null;
        if (usage.has("tokens")) {
            JsonNode t = usage.get("tokens");
            if (!t.isIntegralNumber() || !t.canConvertToLong()) {
                throw new IllegalArgumentException("usage 'tokens' must be an integer: " + t);
            }
            tokens = t.asLong();
        }
        Double costUsd = null;
        if (usage.has("costUsd")) {
            JsonNode c = usage.get("costUsd");
            if (!c.isNumber()) {
                throw new IllegalArgumentException("usage 'costUsd' must be a number: " + c);
            }
            costUsd = c.asDouble();
        }
        return new OperationUsage(tokens, costUsd);
    }

    private static void putUsage(ObjectNode node, OperationUsage usage) {
        if (usage != null) {
            node.set("usage", WorkflowEventJson.mapper().valueToTree(usage));
        }
    }

    private static Object toJava(JsonNode node) {
        return WorkflowEventJson.mapper().convertValue(node, Object.class);
    }

    private static void requireOnlyKeys(JsonNode json, Set<String> allowed, String what) {
        Iterator<String> names = json.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unknown property '" + name + "' in " + what);
            }
        }
    }

    private static String requiredText(JsonNode json, String field, String what) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(what + " requires string '" + field + "'");
        }
        return value.asText();
    }
}
