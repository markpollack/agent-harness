package io.github.markpollack.workflow.spec;

/**
 * Error-envelope matcher for {@code error} edges. At least one criterion must be present
 * (schema {@code minProperties: 1}); both present means both must match.
 */
public record ErrorMatch(String code, Boolean retryable) {

    public ErrorMatch {
        if (code == null && retryable == null) {
            throw new IllegalArgumentException("errorMatch requires at least one of 'code' or 'retryable'");
        }
    }
}
