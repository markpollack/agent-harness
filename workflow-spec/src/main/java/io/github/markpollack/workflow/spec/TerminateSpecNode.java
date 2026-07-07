package io.github.markpollack.workflow.spec;

import java.util.Map;
import java.util.Objects;

/**
 * A {@code terminate} node: ends the workflow with a declared {@link TerminateStatus},
 * optionally binding a final {@code result}. Terminate nodes must have no outgoing
 * edges (alpha spec §16) — enforced by the semantic validator.
 */
public record TerminateSpecNode(
        String id,
        String name,
        String description,
        Map<String, String> annotations,
        TerminateStatus status,
        Binding result) implements WorkflowSpecNode {

    public TerminateSpecNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        annotations = annotations == null ? null : Map.copyOf(annotations);
    }
}
