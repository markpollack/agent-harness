package io.github.markpollack.workflow.engine;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link OperationRegistry}. Registration is fluent and happens before
 * execution; duplicate registration of a ref is rejected rather than replaced —
 * a collision means two behaviors claim one stable identifier (the RISKS.md R1
 * naming-determinism hazard), and silent last-wins would hide exactly the bug the
 * deterministic-ref rules exist to prevent.
 */
public final class SimpleOperationRegistry implements OperationRegistry {

    private final Map<String, OperationHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Registers a handler under a stable ref.
     *
     * @param operationRef the capability identifier specs will reference
     * @param handler      the handler executing one attempt of the operation
     * @return this registry, for chained registration
     * @throws IllegalStateException if the ref is already registered
     */
    public SimpleOperationRegistry register(String operationRef, OperationHandler handler) {
        Objects.requireNonNull(operationRef, "operationRef");
        Objects.requireNonNull(handler, "handler");
        OperationHandler previous = handlers.putIfAbsent(operationRef, handler);
        if (previous != null) {
            throw new IllegalStateException("operation ref already registered: " + operationRef);
        }
        return this;
    }

    @Override
    public OperationHandler resolve(String operationRef) {
        Objects.requireNonNull(operationRef, "operationRef");
        OperationHandler handler = handlers.get(operationRef);
        if (handler == null) {
            throw new UnknownOperationException(operationRef);
        }
        return handler;
    }
}
