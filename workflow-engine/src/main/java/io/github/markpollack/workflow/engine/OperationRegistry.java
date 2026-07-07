package io.github.markpollack.workflow.engine;

/**
 * Resolves a spec-declared {@code operationRef} to the {@link OperationHandler} that
 * executes it — the only door from inert spec data to behavior (DD-3). The ref's
 * {@code <runtime>:} prefix is descriptive metadata; the registered handler decides
 * transport (DD-12).
 *
 * <p>Contract: registration happens before workflow execution, not during; resolution
 * of an unregistered ref fails fast with {@link UnknownOperationException} at dispatch
 * time.
 */
public interface OperationRegistry {

    /**
     * Returns the handler registered for the given ref.
     *
     * @param operationRef the stable capability identifier from the spec's
     *                     {@code operations} section
     * @return the registered handler, never null
     * @throws UnknownOperationException if no handler is registered for the ref
     */
    OperationHandler resolve(String operationRef);
}
