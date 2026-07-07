package io.github.markpollack.workflow.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** Semantic-property matrix for all five attempt states (alpha spec §6/§7). */
class OperationResultTest {

    private static final ErrorEnvelope RETRYABLE_ERROR =
            ErrorEnvelope.of("RATE_LIMIT", "rate limited", true);
    private static final ErrorEnvelope TERMINAL_ERROR =
            ErrorEnvelope.of("BAD_INPUT", "unprocessable", false);

    @Test
    void successProperties() {
        OperationResult result = OperationResult.success("out");

        assertThat(result.status()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(result.terminal()).isTrue();
        assertThat(result.successful()).isTrue();
        assertThat(result.retryable()).isFalse();
        assertThat(result.routable()).isFalse();
        assertThat(((OperationResult.Success) result).output()).isEqualTo("out");
    }

    @Test
    void failureProperties() {
        OperationResult result = OperationResult.failure(RETRYABLE_ERROR);

        assertThat(result.status()).isEqualTo(OperationStatus.FAILURE);
        assertThat(result.terminal()).isTrue();
        assertThat(result.successful()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.routable()).isTrue();
    }

    @Test
    void nonRetryableFailureIsRoutableButNotRetryable() {
        OperationResult result = OperationResult.failure(TERMINAL_ERROR);

        assertThat(result.retryable()).isFalse();
        assertThat(result.routable()).isTrue();
    }

    @Test
    void timedOutProperties() {
        OperationResult result = OperationResult.timedOut(
                ErrorEnvelope.of("OPERATION_TIMEOUT", "exceeded budget", true));

        assertThat(result.status()).isEqualTo(OperationStatus.TIMED_OUT);
        assertThat(result.terminal()).isTrue();
        assertThat(result.successful()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.routable()).isTrue();
    }

    @Test
    void cancelledProperties() {
        OperationResult result = OperationResult.cancelled("workflow_cancelled");

        assertThat(result.status()).isEqualTo(OperationStatus.CANCELLED);
        assertThat(result.terminal()).isTrue();
        assertThat(result.successful()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.routable()).isFalse();
    }

    @Test
    void abortedProperties() {
        OperationResult result = OperationResult.aborted("runtime_invariant_violation");

        assertThat(result.status()).isEqualTo(OperationStatus.ABORTED);
        assertThat(result.terminal()).isTrue();
        assertThat(result.successful()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.routable()).isFalse();
    }

    @Test
    void failureVariantsRequireAnErrorEnvelope() {
        assertThatNullPointerException().isThrownBy(() -> OperationResult.failure(null));
        assertThatNullPointerException().isThrownBy(() -> OperationResult.timedOut(null));
        assertThatNullPointerException().isThrownBy(() -> ErrorEnvelope.of(null, "m", true));
    }

    @Test
    void sealedVariantsAreExhaustivelyMatchable() {
        for (OperationResult result : new OperationResult[]{
                OperationResult.success(null),
                OperationResult.failure(RETRYABLE_ERROR),
                OperationResult.timedOut(RETRYABLE_ERROR),
                OperationResult.cancelled("c"),
                OperationResult.aborted("a")}) {
            String described = switch (result) {
                case OperationResult.Success s -> "success";
                case OperationResult.Failure f -> "failure:" + f.error().code();
                case OperationResult.TimedOut t -> "timed_out:" + t.error().code();
                case OperationResult.Cancelled c -> "cancelled:" + c.reason();
                case OperationResult.Aborted a -> "aborted:" + a.reason();
            };
            assertThat(described).isNotBlank();
        }
    }
}
