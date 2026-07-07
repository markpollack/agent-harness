package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;

import java.util.Objects;
import java.util.Optional;

/**
 * Backs {@link WorkflowContextStore} with the existing {@link AgentContext}, so
 * IR-driven string-keyed access and typed {@link ContextKey} access read and write the
 * same entries: {@code ContextKey} equality is name-only, so a key minted here as
 * {@code ContextKey.of(path, Object.class)} hits an entry stored under a typed key of
 * the same name, and vice versa.
 *
 * <p>Context keys are one flat namespace — an IR context write to the name of a
 * framework well-known key (e.g. {@code workflowRunId}) replaces it; spec authors own
 * that collision, same as any two typed writers would.
 */
public final class AgentContextAdapter implements WorkflowContextStore {

    private final AgentContext context;

    public AgentContextAdapter(AgentContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** The adapted context — what the interpreter hands to operation handlers. */
    public AgentContext context() {
        return context;
    }

    @Override
    public Optional<Object> get(String path) {
        Objects.requireNonNull(path, "path");
        return context.get(ContextKey.of(path, Object.class));
    }

    @Override
    public AgentContextAdapter put(String path, Object value) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        return new AgentContextAdapter(
                context.mutate().with(ContextKey.of(path, Object.class), value).build());
    }
}
