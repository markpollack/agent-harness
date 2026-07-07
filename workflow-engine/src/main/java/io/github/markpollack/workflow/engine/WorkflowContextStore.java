package io.github.markpollack.workflow.engine;

import java.util.Optional;

/**
 * String-keyed context access for IR-driven reads ({@code $context.<key>} bindings,
 * §12) and writes ({@code contextWrites}, §14). Keys are <em>flat</em> strings that may
 * contain dots — {@code pr.diff} is one key, never navigation (the frozen binding
 * grammar).
 *
 * <p>Immutable: {@code put} returns a new store; the interpreter threads the current
 * store through the run. The wire has no {@code null} (DD-15 rule 2), so a value can
 * never be null — absent is the only empty state.
 */
public interface WorkflowContextStore {

    /**
     * The value under a flat key, or empty if absent.
     */
    Optional<Object> get(String path);

    /**
     * Returns a new store with {@code value} under {@code path}; this store is
     * unchanged.
     *
     * @throws NullPointerException if value is null — a null context write cannot exist
     */
    WorkflowContextStore put(String path, Object value);
}
