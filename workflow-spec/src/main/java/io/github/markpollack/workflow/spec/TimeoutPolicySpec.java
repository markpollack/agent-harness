package io.github.markpollack.workflow.spec;

/**
 * Per-attempt timeout budget in milliseconds. Expiry normalizes the attempt to a
 * {@code timed_out} OperationResult, which then flows through ordinary §17 precedence.
 */
public record TimeoutPolicySpec(long perAttemptMillis) {

    public TimeoutPolicySpec {
        if (perAttemptMillis < 1) {
            throw new IllegalArgumentException("perAttemptMillis must be >= 1: " + perAttemptMillis);
        }
    }
}
