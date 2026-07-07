package io.github.markpollack.workflow.engine;

import java.util.Map;
import java.util.Objects;

/**
 * Normalized error carried by {@code failure}/{@code timed_out} results (alpha spec §6).
 * Handlers map their failure modes into this envelope at the operation boundary —
 * error semantics are never recovered from message strings (Argo teardown lesson).
 *
 * <p>{@code retryable} is advisory input to the interpreter's retry policy, not a
 * command. {@code details} may carry cause chains / diagnostics under §11 disclosure
 * rules (shape frozen at Step 2.5).
 */
public record ErrorEnvelope(
        String code,
        String message,
        boolean retryable,
        Map<String, Object> details) {

    public ErrorEnvelope {
        Objects.requireNonNull(code, "code");
        details = details == null ? null : Map.copyOf(details);
    }

    public static ErrorEnvelope of(String code, String message, boolean retryable) {
        return new ErrorEnvelope(code, message, retryable, null);
    }
}
