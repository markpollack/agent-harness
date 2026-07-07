package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.spec.BackoffSpec;
import io.github.markpollack.workflow.spec.ErrorMatcher;
import io.github.markpollack.workflow.spec.RetryPolicySpec;

import java.util.Objects;

/**
 * The §17 Retry Gate as a pure function: no clock, no RNG, no sleeping — delays are a
 * closed-form function of the attempt number, so {@code RetryScheduled.delayMillis} is
 * reproducible and replay-safe ({@code research/retry-design-notes.md}; DD-17).
 *
 * <p>A retry happens iff ALL hold (AND semantics): attempts remain
 * ({@code maxAttempts} counts the first attempt — Framework 7's {@code maxRetries}
 * off-by-one trap avoided), the result state is retryable ({@code failure} /
 * {@code timed_out}), the error envelope says retryable, and {@code retryOn} is absent
 * or exactly matches the error code. No effective policy = single attempt.
 */
public final class RetryDecider {

    private RetryDecider() {
    }

    /**
     * Evaluates the gate for a completed attempt.
     *
     * @param policy        the effective (precedence-resolved) retry policy; null means
     *                      no site declared one — a single attempt (§17)
     * @param result        the normalized outcome of the attempt just completed
     * @param attemptNumber the 1-based number of that attempt
     */
    public static RetryDecision decide(RetryPolicySpec policy, OperationResult result, int attemptNumber) {
        Objects.requireNonNull(result, "result");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1: " + attemptNumber);
        }
        if (!result.routable()) {
            return new RetryDecision.Exhausted("state_not_retryable");
        }
        if (policy == null) {
            return new RetryDecision.Exhausted("no_retry_policy");
        }
        if (attemptNumber >= policy.maxAttempts()) {
            return new RetryDecision.Exhausted("max_attempts");
        }
        if (!result.retryable() || !retryOnMatches(policy, result)) {
            return new RetryDecision.Exhausted("error_not_retryable");
        }
        return new RetryDecision.Retry(delayFor(policy.backoff(), attemptNumber), "policy_allows");
    }

    private static boolean retryOnMatches(RetryPolicySpec policy, OperationResult result) {
        if (policy.retryOn() == null) {
            return true;
        }
        String code = switch (result) {
            case OperationResult.Failure f -> f.error().code();
            case OperationResult.TimedOut t -> t.error().code();
            default -> null; // unreachable: routable() gated above
        };
        return policy.retryOn().stream().map(ErrorMatcher::code).anyMatch(c -> c.equals(code));
    }

    /**
     * Closed-form delay for the completed attempt (§4 Policies): absent backoff →
     * immediate re-dispatch; {@code fixed} → {@code initialMillis}; {@code exponential}
     * → {@code min(round(initialMillis * multiplier^(attemptNumber-1)), maxMillis)},
     * uncapped when {@code maxMillis} is absent.
     */
    static long delayFor(BackoffSpec backoff, int attemptNumber) {
        if (backoff == null) {
            return 0;
        }
        return switch (backoff.strategy()) {
            case FIXED -> backoff.initialMillis();
            case EXPONENTIAL -> {
                double raw = backoff.initialMillis()
                        * Math.pow(backoff.multiplier(), attemptNumber - 1L);
                long delay = Math.round(raw);
                yield backoff.maxMillis() != null ? Math.min(delay, backoff.maxMillis()) : delay;
            }
        };
    }
}
