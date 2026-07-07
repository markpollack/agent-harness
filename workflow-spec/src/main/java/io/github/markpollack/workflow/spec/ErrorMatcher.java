package io.github.markpollack.workflow.spec;

import java.util.Objects;

/** Exact error-code matcher used in {@code retryOn} lists (alpha matches codes exactly). */
public record ErrorMatcher(String code) {

    public ErrorMatcher {
        Objects.requireNonNull(code, "code");
    }
}
