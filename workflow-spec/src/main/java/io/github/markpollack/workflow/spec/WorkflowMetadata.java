package io.github.markpollack.workflow.spec;

import java.util.Map;

/**
 * Workflow identity and classification metadata. {@code labels} and {@code annotations}
 * follow the Kubernetes split (DD-19): labels classify, annotations carry tool-owned
 * metadata the engine MUST ignore and every SDK MUST preserve. Both are string-valued.
 */
public record WorkflowMetadata(
        String name,
        String version,
        Map<String, String> labels,
        Map<String, String> annotations) {

    public WorkflowMetadata {
        name = SpecInvariants.requireNonBlank(name, "name");
        labels = labels == null ? null : Map.copyOf(labels);
        annotations = annotations == null ? null : Map.copyOf(annotations);
    }
}
