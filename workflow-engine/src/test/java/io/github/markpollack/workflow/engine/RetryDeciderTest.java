package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.spec.BackoffSpec;
import io.github.markpollack.workflow.spec.ErrorMatcher;
import io.github.markpollack.workflow.spec.RetryPolicySpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The §17 Retry Gate (AND semantics) and the closed-form delay formulas. */
class RetryDeciderTest {

    private static final OperationResult RETRYABLE =
            OperationResult.failure(ErrorEnvelope.of("RATE_LIMIT", "slow down", true));
    private static final OperationResult NON_RETRYABLE =
            OperationResult.failure(ErrorEnvelope.of("BAD_INPUT", "unprocessable", false));

    private static RetryPolicySpec attempts(int maxAttempts) {
        return new RetryPolicySpec(maxAttempts, null, null);
    }

    @Test
    void noPolicyMeansSingleAttempt() {
        RetryDecision decision = RetryDecider.decide(null, RETRYABLE, 1);

        assertThat(decision).isEqualTo(new RetryDecision.Exhausted("no_retry_policy"));
    }

    @Test
    void maxAttemptsCountsTheFirstAttempt() {
        RetryPolicySpec policy = attempts(3);

        assertThat(RetryDecider.decide(policy, RETRYABLE, 1))
                .isEqualTo(new RetryDecision.Retry(0, "policy_allows"));
        assertThat(RetryDecider.decide(policy, RETRYABLE, 2))
                .isEqualTo(new RetryDecision.Retry(0, "policy_allows"));
        assertThat(RetryDecider.decide(policy, RETRYABLE, 3))
                .isEqualTo(new RetryDecision.Exhausted("max_attempts"));
    }

    @Test
    void nonRetryableStatesExhaustImmediately() {
        RetryPolicySpec policy = attempts(5);

        assertThat(RetryDecider.decide(policy, OperationResult.success("out"), 1))
                .isEqualTo(new RetryDecision.Exhausted("state_not_retryable"));
        assertThat(RetryDecider.decide(policy, OperationResult.cancelled("stop"), 1))
                .isEqualTo(new RetryDecision.Exhausted("state_not_retryable"));
        assertThat(RetryDecider.decide(policy, OperationResult.aborted("invariant"), 1))
                .isEqualTo(new RetryDecision.Exhausted("state_not_retryable"));
    }

    @Test
    void envelopeRetryableFlagGates() {
        assertThat(RetryDecider.decide(attempts(5), NON_RETRYABLE, 1))
                .isEqualTo(new RetryDecision.Exhausted("error_not_retryable"));
    }

    @Test
    void retryOnRequiresExactCodeMatch() {
        RetryPolicySpec matching = new RetryPolicySpec(5, null,
                List.of(new ErrorMatcher("RATE_LIMIT")));
        RetryPolicySpec other = new RetryPolicySpec(5, null,
                List.of(new ErrorMatcher("TIMEOUT_ISH")));

        assertThat(RetryDecider.decide(matching, RETRYABLE, 1))
                .isEqualTo(new RetryDecision.Retry(0, "policy_allows"));
        assertThat(RetryDecider.decide(other, RETRYABLE, 1))
                .isEqualTo(new RetryDecision.Exhausted("error_not_retryable"));
    }

    @Test
    void timedOutIsRetryableFamilyToo() {
        OperationResult timedOut = OperationResult.timedOut(
                ErrorEnvelope.of("OPERATION_TIMEOUT", "budget", true));

        assertThat(RetryDecider.decide(attempts(2), timedOut, 1))
                .isEqualTo(new RetryDecision.Retry(0, "policy_allows"));
    }

    @Test
    void fixedBackoffIsConstant() {
        BackoffSpec fixed = new BackoffSpec(BackoffSpec.Strategy.FIXED, 250, null, null);

        assertThat(RetryDecider.delayFor(fixed, 1)).isEqualTo(250);
        assertThat(RetryDecider.delayFor(fixed, 7)).isEqualTo(250);
    }

    @Test
    void exponentialBackoffIsClosedFormAndCapped() {
        BackoffSpec exp = new BackoffSpec(BackoffSpec.Strategy.EXPONENTIAL, 500, 10000L, 2.0);

        assertThat(RetryDecider.delayFor(exp, 1)).isEqualTo(500);
        assertThat(RetryDecider.delayFor(exp, 2)).isEqualTo(1000);
        assertThat(RetryDecider.delayFor(exp, 3)).isEqualTo(2000);
        assertThat(RetryDecider.delayFor(exp, 6)).isEqualTo(10000);
    }

    @Test
    void exponentialUncappedWhenMaxAbsent() {
        BackoffSpec exp = new BackoffSpec(BackoffSpec.Strategy.EXPONENTIAL, 500, null, 2.0);

        assertThat(RetryDecider.delayFor(exp, 6)).isEqualTo(16000);
    }

    @Test
    void absentBackoffMeansImmediateRedispatch() {
        assertThat(RetryDecider.delayFor(null, 3)).isZero();
    }
}
