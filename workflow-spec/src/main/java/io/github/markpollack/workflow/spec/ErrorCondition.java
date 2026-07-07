package io.github.markpollack.workflow.spec;

import java.util.Objects;

/**
 * Selected after retry exhaustion when the most recent failed/timed-out
 * {@code OperationResult}'s error envelope matches {@link #match()} (alpha spec §17).
 */
public record ErrorCondition(ErrorMatch match) implements EdgeConditionSpec {

    public ErrorCondition {
        Objects.requireNonNull(match, "match");
    }
}
