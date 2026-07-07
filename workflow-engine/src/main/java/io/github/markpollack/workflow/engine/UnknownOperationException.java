package io.github.markpollack.workflow.engine;

import java.util.Objects;

/**
 * Thrown by {@link OperationRegistry#resolve} when no handler is registered for an
 * {@code operationRef}. Semantic validation guarantees every node references a
 * <em>declared</em> operation; whether the declared ref is <em>registered</em> is only
 * knowable at dispatch time — this exception is that fail-fast signal.
 */
public class UnknownOperationException extends RuntimeException {

    private final String operationRef;

    public UnknownOperationException(String operationRef) {
        super("no operation handler registered for ref: " + operationRef);
        this.operationRef = Objects.requireNonNull(operationRef, "operationRef");
    }

    public String operationRef() {
        return operationRef;
    }
}
