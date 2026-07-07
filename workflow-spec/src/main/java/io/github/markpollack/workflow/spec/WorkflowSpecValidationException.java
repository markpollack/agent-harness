package io.github.markpollack.workflow.spec;

import java.util.List;

/**
 * Thrown when a spec fails either validation phase. Carries every collected
 * {@link ValidationError} (validators collect all errors; they never stop at the first).
 */
public class WorkflowSpecValidationException extends RuntimeException {

    private final transient List<ValidationError> errors;

    public WorkflowSpecValidationException(List<ValidationError> errors) {
        super(summarize(errors));
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }

    private static String summarize(List<ValidationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "spec validation failed";
        }
        var first = errors.get(0);
        var head = first.code() + (first.path() == null ? "" : " at " + first.path()) + ": " + first.message();
        return errors.size() == 1 ? head : head + " (+" + (errors.size() - 1) + " more)";
    }
}
