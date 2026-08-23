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
     * Evaluates the output.
     * <p>
     * The return is a {@link GateAssessment} rather than a bare {@link GateDecision} because a
     * returned jury verdict does not always contain a pass/fail finding. The assessment represents
     * every status of a returned verdict; it does not represent unchecked failures that occur
     * before a verdict exists. A gate that always decides — an approval, a predicate — says so
     * with {@link GateAssessment#decided(GateDecision)}.
     *
     * @param ctx    the execution context
     * @param output the output to evaluate
     * @return what this gate concluded
     */
    GateAssessment evaluate(AgentContext ctx, O output);

    /**
     * Called by the executor immediately after {@link #evaluate} reaches a decision, before
     * routing. Override to write gate results (judgment, score, decision) to context under typed
     * keys, eliminating the need for a separate step to record gate output.
     * <p>
     * An evaluation that reached no decision never arrives here — there is no decision to pass —
     * and the executor writes its verdict evidence itself.
     *
     * @param ctx      the current context
     * @param output   the output that was evaluated
     * @param decision the decision {@link #evaluate} reached
     * @return updated context (or {@code ctx} unchanged if nothing to write)
     */
    default AgentContext updateContext(AgentContext ctx, O output, GateDecision decision) {
        return ctx;
    }
}
