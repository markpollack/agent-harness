package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The closed edge-condition algebra of v2-alpha: {@code always}, {@code decisionResult},
 * {@code error}. Discriminated on the wire by the {@code kind} property.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AlwaysCondition.class, name = "always"),
        @JsonSubTypes.Type(value = DecisionResultCondition.class, name = "decisionResult"),
        @JsonSubTypes.Type(value = ErrorCondition.class, name = "error")
})
public sealed interface EdgeConditionSpec permits AlwaysCondition, DecisionResultCondition, ErrorCondition {
}
