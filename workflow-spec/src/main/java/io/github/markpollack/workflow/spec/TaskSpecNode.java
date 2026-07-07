package io.github.markpollack.workflow.spec;

import java.util.Map;

/**
 * A {@code task} node: dispatches one operation (by alias into the top-level
 * {@code operations} map), optionally binding inputs and writing declared context keys.
 */
public record TaskSpecNode(
        String id,
        String name,
        String description,
        Map<String, String> annotations,
        String operation,
        Map<String, Binding> input,
        Map<String, Binding> contextWrites,
        PolicyBundle policies) implements WorkflowSpecNode {

    public TaskSpecNode {
        id = SpecInvariants.requireNodeId(id);
        operation = SpecInvariants.requireNonBlank(operation, "operation");
        annotations = annotations == null ? null : Map.copyOf(annotations);
        input = input == null ? null : Map.copyOf(input);
        contextWrites = contextWrites == null ? null : Map.copyOf(contextWrites);
    }
}
