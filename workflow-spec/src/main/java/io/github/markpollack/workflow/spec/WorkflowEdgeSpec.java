package io.github.markpollack.workflow.spec;

import java.util.Objects;

/** A directed edge: source node, target node, and the condition under which it is selected. */
public record WorkflowEdgeSpec(
        String from,
        String to,
        EdgeConditionSpec when,
        String label) {

    public WorkflowEdgeSpec {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(when, "when");
    }
}
