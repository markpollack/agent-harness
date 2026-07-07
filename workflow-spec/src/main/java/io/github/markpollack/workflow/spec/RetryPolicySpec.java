package io.github.markpollack.workflow.spec;

import java.util.List;

/**
 * Declarative retry policy (interpreter-owned per DD-17; evaluated by the engine's
 * stateless decider at Step 2.4).
 *
 * <p>{@link #maxAttempts()} is the <em>total</em> number of attempts including the
 * first — not a retries-after-the-first count (the classic off-by-one trap;
 * {@code research/retry-design-notes.md} §3).
 *
 * <p>{@link #retryOn()} is an optional list of error-code matchers; absent means any
 * retryable error qualifies. It combines with the error envelope's {@code retryable}
 * flag with AND semantics (pinned in the alpha spec at Step 1.6).
 */
public record RetryPolicySpec(
        int maxAttempts,
        BackoffSpec backoff,
        List<ErrorMatcher> retryOn) {

    public RetryPolicySpec {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        if (retryOn != null) {
            if (retryOn.isEmpty()) {
                throw new IllegalArgumentException("retryOn must not be empty when present");
            }
            retryOn = List.copyOf(retryOn);
        }
    }
}
