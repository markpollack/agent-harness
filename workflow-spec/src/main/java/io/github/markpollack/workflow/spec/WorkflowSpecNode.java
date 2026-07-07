package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * The closed node algebra of the v2-alpha WorkflowSpec: {@code task}, {@code decision},
 * {@code terminate}. Discriminated on the wire by the {@code kind} property.
 *
 * <p>The spec is inert data (alpha spec §4): nodes carry no behavior, only an operation
 * alias resolved at runtime through the {@code OperationRegistry}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TaskSpecNode.class, name = "task"),
        @JsonSubTypes.Type(value = DecisionSpecNode.class, name = "decision"),
        @JsonSubTypes.Type(value = TerminateSpecNode.class, name = "terminate")
})
public sealed interface WorkflowSpecNode permits TaskSpecNode, DecisionSpecNode, TerminateSpecNode {

    /** Workflow-position identity: checkpoint key, event identity, trace anchor (DD-7). */
    String id();

    /** Optional display name. */
    String name();

    /** Optional description. */
    String description();

    /**
     * Sanctioned extension point (DD-19): string-valued, engine MUST ignore,
     * SDKs MUST preserve losslessly.
     */
    Map<String, String> annotations();
}
