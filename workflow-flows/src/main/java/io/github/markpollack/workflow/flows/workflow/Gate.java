package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;

/**
 * An approval or quality check between workflow steps.
 * <p>
 * Unifies two patterns under one abstraction:
 * <ul>
 *   <li>{@code JudgeGate} — automated quality gate (score >= threshold)</li>
 *   <li>{@code HumanGate} — HITL approval (blocks until external signal)</li>
 *   <li>{@code TieredGate} — auto-approve high scores, escalate borderline</li>
 * </ul>
 * Same DSL surface regardless of implementation:
 * {@code .gate(g).onPass(step).onFail(step).end()}
 *
 * @param <O> the type of output being evaluated
 */
@FunctionalInterface
public interface Gate<O> {

    /**
     * Evaluates the output and returns a routing decision.
     *
     * @param ctx    the execution context
     * @param output the output to evaluate
     * @return the gate decision (PASS, FAIL, ESCALATE, or TIMEOUT)
     */
    GateDecision evaluate(AgentContext ctx, O output);

    /**
     * Called by the executor immediately after {@link #evaluate}, before routing.
     * Override to write gate results (judgment, score, decision) to context under typed keys,
     * eliminating the need for a separate step to record gate output.
     *
     * @param ctx      the current context
     * @param output   the output that was evaluated
     * @param decision the decision returned by {@link #evaluate}
     * @return updated context (or {@code ctx} unchanged if nothing to write)
     */
    default AgentContext updateContext(AgentContext ctx, O output, GateDecision decision) {
        return ctx;
    }
}
