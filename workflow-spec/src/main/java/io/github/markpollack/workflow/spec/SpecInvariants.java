package io.github.markpollack.workflow.spec;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cheap local invariants shared by the model records — the constructor-side mirror of
 * the schema's string constraints, so directly constructed specs cannot silently emit
 * wire-invalid bytes. Cross-field/graph rules stay in {@link WorkflowSpecValidator}.
 */
final class SpecInvariants {

    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private SpecInvariants() {
    }

    static String requireNodeId(String id) {
        Objects.requireNonNull(id, "id");
        if (!NODE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "node id must match [A-Za-z0-9_-]+ (no dots; binding grammar, alpha spec §4/§12): '" + id + "'");
        }
        return id;
    }

    static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static void requireUnique(List<String> values, String field) {
        if (values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates: " + values);
        }
    }
}
