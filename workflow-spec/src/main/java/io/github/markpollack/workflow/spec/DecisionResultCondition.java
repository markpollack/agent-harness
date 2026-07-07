package io.github.markpollack.workflow.spec;

import java.util.Objects;

/**
 * Selected when the source decision node's stored outcome equals {@link #value()}.
 * The value must be one of the decision node's declared outcomes (semantic rule).
 */
public record DecisionResultCondition(String value) implements EdgeConditionSpec {

    public DecisionResultCondition {
        Objects.requireNonNull(value, "value");
    }
}
