package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * The §11 disclosure form carried by value-bearing events instead of raw payloads —
 * conforming runtimes MUST NOT casually expose payload values in canonical events.
 *
 * <p>Alpha implements the {@code metadata_only} mode (type + size, no value, no hash).
 * The {@code hmac_hash} mode needs a disclosure policy and key management that alpha
 * does not have; it stays unimplemented until a policy layer exists. The wire shape of
 * this form is reconciled and frozen at Step 2.5.
 *
 * <p>{@code type} uses JSON-family type names ({@code string}, {@code number},
 * {@code boolean}, {@code object}, {@code array}, {@code null}) — never Java class
 * names (RISKS.md R7: a Python worker must be able to produce every wire value
 * verbatim). {@code sizeBytes} is the UTF-8 byte length for strings and absent for
 * other types in alpha (a deterministic cross-language size for arbitrary objects
 * needs canonical-form serialization — deferred to 2.5 with the wire projection).
 */
public record ValueDisclosure(
        String mode,
        String type,
        Long sizeBytes,
        String reason) {

    /** The only disclosure mode alpha emits. */
    public static final String METADATA_ONLY = "metadata_only";

    public ValueDisclosure {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(type, "type");
    }

    /** Metadata-only disclosure of a value under the default alpha policy. */
    public static ValueDisclosure metadataOnly(Object value) {
        Long sizeBytes = value instanceof String s
                ? (long) s.getBytes(StandardCharsets.UTF_8).length
                : null;
        return new ValueDisclosure(METADATA_ONLY, jsonTypeName(value), sizeBytes, "default_policy");
    }

    /** JSON-family type name of a runtime value. */
    static String jsonTypeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof JsonNode node) {
            return switch (node.getNodeType()) {
                case STRING -> "string";
                case NUMBER -> "number";
                case BOOLEAN -> "boolean";
                case ARRAY -> "array";
                case NULL, MISSING -> "null";
                default -> "object";
            };
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Collection<?> || value.getClass().isArray()) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return "object";
    }
}
