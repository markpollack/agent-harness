package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Deterministic backoff: delays are a closed-form function of the attempt number —
 * no clock, no RNG, no jitter in alpha (determinism hazard for event conformance;
 * {@code research/retry-design-notes.md} §3).
 *
 * <p>Cross-field rules ({@code exponential} requires {@code multiplier};
 * {@code initialMillis <= maxMillis} when both present) are semantic-validator rules.
 */
public record BackoffSpec(
        Strategy strategy,
        long initialMillis,
        Long maxMillis,
        Double multiplier) {

    public BackoffSpec {
        Objects.requireNonNull(strategy, "strategy");
        if (initialMillis < 0) {
            throw new IllegalArgumentException("initialMillis must be >= 0: " + initialMillis);
        }
        if (maxMillis != null && maxMillis < 0) {
            throw new IllegalArgumentException("maxMillis must be >= 0: " + maxMillis);
        }
        if (multiplier != null && multiplier <= 0) {
            throw new IllegalArgumentException("multiplier must be > 0: " + multiplier);
        }
    }

    /** Alpha backoff strategies. */
    public enum Strategy {
        @JsonProperty("fixed")
        FIXED,

        @JsonProperty("exponential")
        EXPONENTIAL
    }
}
