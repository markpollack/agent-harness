package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.core.AgentContext;

/**
 * The operation plane: how one attempt of a unit of work does its work (DD-16).
 * Implementations execute <em>exactly one attempt</em> and express every outcome
 * through a normalized {@link OperationResult}.
 *
 * <p>Contract (DESIGN.md § OperationHandler):
 * <ul>
 *   <li>Never retries — the interpreter owns the retry loop universally (DD-17).</li>
 *   <li>Never routes — handlers never see edges or policies.</li>
 *   <li>Expected not to throw: failure modes are normalized into the error envelope at
 *       this boundary. The interpreter nonetheless survives a throwing handler by
 *       normalizing to {@code failure(code=UNHANDLED_EXCEPTION, retryable=false)}
 *       (alpha spec §19) — that defensive net is the interpreter's contract, not a
 *       licence for handlers to throw.</li>
 *   <li>Side effects belong here; operations with external effects must be idempotent
 *       or dedup-guarded using the {@link OperationInvocation} identity.</li>
 * </ul>
 */
@FunctionalInterface
public interface OperationHandler {

    /**
     * Executes one attempt of the operation.
     *
     * @param invocation execution identity (run, node, ref, attempt)
     * @param context    the ambient metadata carrier for this run
     * @param input      the evaluated input bindings for this dispatch (may be null)
     * @return the normalized outcome of this attempt, never null
     */
    OperationResult execute(OperationInvocation invocation, AgentContext context, Object input);
}
