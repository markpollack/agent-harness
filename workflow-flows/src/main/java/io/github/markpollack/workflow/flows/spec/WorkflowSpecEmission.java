package io.github.markpollack.workflow.flows.spec;

import io.github.markpollack.workflow.engine.OperationHandler;
import io.github.markpollack.workflow.engine.SimpleOperationRegistry;
import io.github.markpollack.workflow.spec.WorkflowSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The result of {@code .toSpec()}: the inert {@link WorkflowSpec} plus the behaviors
 * the emitter swallowed from the DSL, keyed by their structure-derived operation refs
 * (DD-21). The spec alone is the portable artifact; the handlers are what make it
 * executable in this JVM.
 *
 * @param spec          the emitted spec — semantically valid by emitter self-check
 * @param handlersByRef auto-registered behaviors, in deterministic emission order
 */
public record WorkflowSpecEmission(WorkflowSpec spec, Map<String, OperationHandler> handlersByRef) {

    public WorkflowSpecEmission {
        Objects.requireNonNull(spec, "spec");
        handlersByRef = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(handlersByRef, "handlersByRef")));
    }

    /**
     * Registers every swallowed behavior into the given registry. A duplicate-ref
     * rejection here is the R1 naming-determinism hazard surfacing at exactly the
     * right place — never suppress it by pre-clearing the registry.
     *
     * @return the registry, for chaining into an interpreter
     * @throws IllegalStateException if a ref is already registered with the registry
     */
    public SimpleOperationRegistry registerInto(SimpleOperationRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        handlersByRef.forEach(registry::register);
        return registry;
    }
}
