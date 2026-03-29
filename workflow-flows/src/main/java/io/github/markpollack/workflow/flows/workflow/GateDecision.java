package io.github.markpollack.workflow.flows.workflow;

/**
 * The outcome of a {@link Gate} evaluation.
 */
public enum GateDecision {
    /** Output meets quality/approval threshold — proceed on the pass path. */
    PASS,
    /** Output does not meet threshold — route to the fail/retry path. */
    FAIL,
    /** Output is borderline — escalate to a higher authority (human or senior judge). */
    ESCALATE,
    /** Gate evaluation timed out (typically HumanGate waiting for external signal). */
    TIMEOUT
}
