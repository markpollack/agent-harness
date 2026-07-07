package io.github.markpollack.workflow.spec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@code decision} node: dispatches a routing operation whose successful result must
 * carry {@code output.outcome} matching one of the declared {@link #outcomes()}
 * (alpha spec §15). Outcomes are stable identifiers — renaming one is a breaking change.
 */
public record DecisionSpecNode(
        String id,
        String name,
        String description,
        Map<String, String> annotations,
        String operation,
        Map<String, Binding> input,
        List<String> outcomes,
        PolicyBundle policies) implements WorkflowSpecNode {

    public DecisionSpecNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(outcomes, "outcomes");
        if (outcomes.isEmpty()) {
            throw new IllegalArgumentException("decision node '" + id + "' must declare at least one outcome");
        }
        annotations = annotations == null ? null : Map.copyOf(annotations);
        input = input == null ? null : Map.copyOf(input);
        outcomes = List.copyOf(outcomes);
    }
}
