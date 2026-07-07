package io.github.markpollack.workflow.spec;

/** Exact error-code matcher used in {@code retryOn} lists (alpha matches codes exactly). */
public record ErrorMatcher(String code) {

    public ErrorMatcher {
        code = SpecInvariants.requireNonBlank(code, "code");
    }
}
