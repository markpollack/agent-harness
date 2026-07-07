package io.github.markpollack.workflow.flows;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.engine.ErrorEnvelope;
import io.github.markpollack.workflow.engine.OperationHandler;
import io.github.markpollack.workflow.engine.OperationInvocation;
import io.github.markpollack.workflow.engine.OperationResult;

import java.util.Map;
import java.util.Objects;

/**
 * Adapts the existing {@link Step} library (ClaudeStep, ChatClientStep, plain
 * functions, …) as one v2 {@link OperationHandler} among others — an adapter, not an
 * architectural seam (DD-16). One {@code step.execute()} call per dispatch; no
 * {@code StepRunner} or other v1 runtime machinery is touched.
 *
 * <p>A thrown exception is the Step library's documented failure channel, so catching
 * it here is this handler doing its normalization job — distinct from the
 * interpreter's defensive {@code UNHANDLED_EXCEPTION} boundary, which exists for
 * handlers that break their contract. Normalized failures carry code
 * {@link #STEP_EXECUTION_FAILED} with {@code retryable: true}: the envelope flag is
 * advisory (§6) and a Step's throw says nothing about permanence, so the actual retry
 * decision is left to declared policy — no policy anywhere still means a single
 * attempt (§17).
 *
 * <p>{@link Step#updateContext} is deliberately <em>not</em> invoked: a handler
 * returns an {@link OperationResult} and cannot return a context — in v2 all context
 * mutation is IR-declared ({@code contextWrites}, §14). Steps that publish metadata
 * through {@code updateContext} lose that side channel when run as v2 operations;
 * that is the DD-16 boundary, not an oversight.
 */
public final class StepOperationHandler implements OperationHandler {

    /** Stable error code for a Step that threw; the exception class rides in {@code details}. */
    public static final String STEP_EXECUTION_FAILED = "STEP_EXECUTION_FAILED";

    private final Step<Object, Object> step;

    @SuppressWarnings("unchecked")
    public StepOperationHandler(Step<?, ?> step) {
        this.step = (Step<Object, Object>) Objects.requireNonNull(step, "step");
    }

    @Override
    public OperationResult execute(OperationInvocation invocation, AgentContext context, Object input) {
        try {
            return OperationResult.success(step.execute(context, input));
        } catch (Exception ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return OperationResult.failure(new ErrorEnvelope(
                    STEP_EXECUTION_FAILED,
                    message,
                    true,
                    Map.of("exceptionClass", ex.getClass().getName())));
        }
    }
}
