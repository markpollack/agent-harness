package io.github.markpollack.workflow.engine;

import java.util.Objects;

/**
 * The normalized outcome of exactly one operation attempt (alpha spec §6). Every attempt
 * — however it ends — resolves to one of the five sealed variants; handlers never throw
 * past the interpreter's defensive boundary.
 *
 * <p>Sealed variants (rather than one record with nullable fields) make invalid
 * combinations unrepresentable: a success cannot carry an error envelope, a failure
 * cannot carry an output. The wire projection (§6 shapes, frozen at Step 2.5) maps each
 * variant to its status-discriminated JSON form.
 *
 * <p>The semantic properties are <em>taxonomic</em>, not policy: {@link #retryable()}
 * says the state is in the retryable family and the error envelope does not forbid
 * retry — whether a retry actually happens is the interpreter's {@code RetryDecider}
 * applying the spec's policy (§17; DD-17). Policy never lives in this type.
 */
public sealed interface OperationResult
        permits OperationResult.Success, OperationResult.Failure, OperationResult.TimedOut,
                OperationResult.Cancelled, OperationResult.Aborted {

    OperationStatus status();

    /**
     * Whether execution of this attempt is done. Every alpha state is attempt-terminal;
     * the property exists so interpreter code guards on it and a future non-terminal
     * state (e.g. streaming progress) slots in without call-site changes.
     */
    default boolean terminal() {
        return true;
    }

    /** The operation completed normally. */
    default boolean successful() {
        return this instanceof Success;
    }

    /**
     * The attempt is in the retryable family: {@code failure} or {@code timed_out}
     * whose error envelope does not mark itself non-retryable (§7 state families;
     * §17's Retry Gate item 3 for the envelope flag). Advisory input to the retry
     * policy, never a command.
     */
    default boolean retryable() {
        return switch (this) {
            case Failure f -> f.error().retryable();
            case TimedOut t -> t.error().retryable();
            default -> false;
        };
    }

    /**
     * May route through {@code error} edges after retry exhaustion: {@code failure} and
     * {@code timed_out} only. {@code cancelled} and {@code aborted} are not routable (§7).
     */
    default boolean routable() {
        return this instanceof Failure || this instanceof TimedOut;
    }

    static Success success(Object output) {
        return new Success(output);
    }

    static Failure failure(ErrorEnvelope error) {
        return new Failure(error);
    }

    static TimedOut timedOut(ErrorEnvelope error) {
        return new TimedOut(error);
    }

    static Cancelled cancelled(String reason) {
        return new Cancelled(reason);
    }

    static Aborted aborted(String reason) {
        return new Aborted(reason);
    }

    /** Operation completed normally; {@code output} may be null for void-like operations. */
    record Success(Object output) implements OperationResult {
        @Override
        public OperationStatus status() {
            return OperationStatus.SUCCESS;
        }
    }

    /** Operation completed unsuccessfully with a normalized error envelope. */
    record Failure(ErrorEnvelope error) implements OperationResult {
        public Failure {
            Objects.requireNonNull(error, "error");
        }

        @Override
        public OperationStatus status() {
            return OperationStatus.FAILURE;
        }
    }

    /** Timeout policy produced a terminal attempt result (per-attempt budget expired). */
    record TimedOut(ErrorEnvelope error) implements OperationResult {
        public TimedOut {
            Objects.requireNonNull(error, "error");
        }

        @Override
        public OperationStatus status() {
            return OperationStatus.TIMED_OUT;
        }
    }

    /** External or controller-driven intentional stop; terminates the workflow (§7). */
    record Cancelled(String reason) implements OperationResult {
        @Override
        public OperationStatus status() {
            return OperationStatus.CANCELLED;
        }
    }

    /** Interpreter or runtime could not safely continue; terminates immediately (§7). */
    record Aborted(String reason) implements OperationResult {
        @Override
        public OperationStatus status() {
            return OperationStatus.ABORTED;
        }
    }
}
