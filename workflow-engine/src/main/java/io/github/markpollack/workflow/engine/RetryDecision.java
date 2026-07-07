package io.github.markpollack.workflow.engine;

import java.util.Objects;

/**
 * The outcome of one retry-gate evaluation (§17): retry after a deterministic delay, or
 * exhaustion — a value the interpreter routes into {@code error} edges, never an
 * exception ({@code research/retry-design-notes.md} §3).
 */
public sealed interface RetryDecision {

    /** Re-dispatch after {@code delayMillis}; the interpreter owns the wait. */
    record Retry(long delayMillis, String reason) implements RetryDecision {
        public Retry {
            if (delayMillis < 0) {
                throw new IllegalArgumentException("delayMillis must be >= 0: " + delayMillis);
            }
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** No retry; {@code reason} names the failed gate item. */
    record Exhausted(String reason) implements RetryDecision {
        public Exhausted {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
