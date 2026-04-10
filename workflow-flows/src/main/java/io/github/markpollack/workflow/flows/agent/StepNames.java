package io.github.markpollack.workflow.flows.agent;

import io.github.markpollack.workflow.core.StepName;
import io.github.markpollack.workflow.flows.Step;

/**
 * Utility for resolving the effective name of a {@link Step}.
 * <p>
 * Reads the {@link StepName} annotation via reflection; falls back to
 * {@link Step#name()} if the annotation is absent.
 */
public final class StepNames {

    private StepNames() {
        // utility class
    }

    /**
     * Resolves the effective name for the given step.
     * <p>
     * If the step's class is annotated with {@link StepName}, returns the
     * annotation's value. Otherwise, returns {@link Step#name()}.
     *
     * @param step the step to resolve
     * @return the resolved name
     */
    public static String resolve(Step<?, ?> step) {
        StepName ann = step.getClass().getAnnotation(StepName.class);
        return ann != null ? ann.value() : step.name();
    }
}
