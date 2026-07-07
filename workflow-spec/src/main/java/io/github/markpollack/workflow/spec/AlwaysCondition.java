package io.github.markpollack.workflow.spec;

/** Unconditional edge: selected whenever the source node completes successfully. */
public record AlwaysCondition() implements EdgeConditionSpec {
}
