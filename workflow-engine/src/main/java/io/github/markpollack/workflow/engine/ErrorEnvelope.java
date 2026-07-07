package io.github.markpollack.workflow.engine;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Normalized error carried by {@code failure}/{@code timed_out} results (alpha spec §6).
 * Handlers map their failure modes into this envelope at the operation boundary —
 * error semantics are never recovered from message strings (Argo teardown lesson).
 *
 * <p>{@code retryable} is advisory input to the interpreter's retry policy, not a
 * command. {@code origin} is the optional crashed-vs-failed distinction both Argo and
 * Prefect converged on ({@code infra} = the environment broke, {@code code} = the
 * operation's own logic failed) — envelope metadata, never a sixth result state;
 * producers set it when they know it and omit it when they don't. {@code details} may
 * carry cause chains / diagnostics under §11 disclosure rules.
 */
public record ErrorEnvelope(
        String code,
        String message,
        boolean retryable,
        String origin,
        Map<String, Object> details) {

    /** The environment failed (network, process death, quota). */
    public static final String ORIGIN_INFRA = "infra";

    /** The operation's own logic failed (bad input, assertion, bug). */
    public static final String ORIGIN_CODE = "code";

    private static final Set<String> ORIGINS = Set.of(ORIGIN_INFRA, ORIGIN_CODE);

    public ErrorEnvelope {
        Objects.requireNonNull(code, "code");
        if (origin != null && !ORIGINS.contains(origin)) {
            throw new IllegalArgumentException("origin must be 'infra' or 'code': " + origin);
        }
        details = details == null ? null : Map.copyOf(details);
    }

    public static ErrorEnvelope of(String code, String message, boolean retryable) {
        return new ErrorEnvelope(code, message, retryable, null, null);
    }
}
