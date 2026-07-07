package io.github.markpollack.workflow.engine;

import java.util.Map;
import java.util.Objects;

/**
 * The Java-side summary of a finished run — a convenience for callers; the canonical
 * record of what happened is the event stream (§10).
 *
 * @param terminalState {@code completed} | {@code failed} | {@code cancelled} |
 *                      {@code aborted} (§9 terminal workflow state, wire form)
 * @param reason        why a non-completed run ended (failure reason; null when completed)
 * @param result        the terminate node's bound {@code result} value, if any
 * @param outputs       the evaluated workflow {@code outputs} map (completed runs with a
 *                      declared {@code outputs} section only)
 */
public record WorkflowRunOutcome(
        String terminalState,
        String reason,
        Object result,
        Map<String, Object> outputs) {

    public WorkflowRunOutcome {
        Objects.requireNonNull(terminalState, "terminalState");
        outputs = outputs == null ? null : Map.copyOf(outputs);
    }

    public boolean completed() {
        return "completed".equals(terminalState);
    }
}
