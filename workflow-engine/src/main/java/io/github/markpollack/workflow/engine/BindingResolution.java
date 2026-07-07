package io.github.markpollack.workflow.engine;

import java.util.Objects;

/**
 * The outcome of evaluating one binding source: a value, or a deterministic failure
 * (§13 — same spec, same state, same failure). Failures are values, not exceptions:
 * the interpreter routes them into node failure per §13, and the first failing binding
 * in lexicographic order stops evaluation.
 */
public sealed interface BindingResolution {

    static Resolved resolved(Object value) {
        return new Resolved(value);
    }

    static Failed failed(String reason) {
        return new Failed(reason);
    }

    /** The source resolved to a (never-null) value. */
    record Resolved(Object value) implements BindingResolution {
        public Resolved {
            Objects.requireNonNull(value, "value — absent is the only empty state");
        }
    }

    /** Deterministic failure; {@code reason} names what was missing or malformed. */
    record Failed(String reason) implements BindingResolution {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
