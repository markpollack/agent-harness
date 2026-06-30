package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.score.NumericalScore;
import io.github.markpollack.judge.score.Score;

import java.util.Objects;
import java.util.function.BiFunction;

import io.github.markpollack.judge.context.JudgmentContext;

/**
 * Three-tier quality gate: PASS if score &gt;= highThreshold, ESCALATE if &gt;= lowThreshold, FAIL otherwise.
 * <p>
 * Like {@link JudgeGate}, backed by an {@code agent-judge} Jury, and like {@link JudgeGate} the
 * {@link JudgmentContext} the jury votes on is produced by a required <em>context mapper</em> — see
 * {@link JudgeGate} for why. The ESCALATE path is for borderline results that need human review.
 *
 * @param <O> the type of output being evaluated
 */
public class TieredGate<O> implements Gate<O> {

    private final Jury jury;
    private final double highThreshold;
    private final double lowThreshold;
    private final BiFunction<AgentContext, O, JudgmentContext> contextMapper;
    private volatile Verdict lastVerdict;

    /**
     * @param jury          the jury that votes on the mapped context
     * @param highThreshold PASS if the aggregated score &gt;= this value
     * @param lowThreshold  ESCALATE if the aggregated score &gt;= this value (and &lt; highThreshold)
     * @param contextMapper maps {@code (AgentContext, output)} to the {@link JudgmentContext} the jury
     *                      votes on — populate {@code metadata}/{@code workspace} here
     */
    public TieredGate(Jury jury, double highThreshold, double lowThreshold,
            BiFunction<AgentContext, O, JudgmentContext> contextMapper) {
        this.jury = Objects.requireNonNull(jury, "jury");
        if (lowThreshold > highThreshold) {
            throw new IllegalArgumentException(
                    "lowThreshold (" + lowThreshold + ") must be <= highThreshold (" + highThreshold + ")");
        }
        this.highThreshold = highThreshold;
        this.lowThreshold = lowThreshold;
        this.contextMapper = Objects.requireNonNull(contextMapper, "contextMapper");
    }

    @Override
    public GateDecision evaluate(AgentContext ctx, O output) {
        JudgmentContext judgmentCtx = this.contextMapper.apply(ctx, output);

        lastVerdict = jury.vote(judgmentCtx);
        double score = extractScore(lastVerdict);

        if (score >= highThreshold) return GateDecision.PASS;
        if (score >= lowThreshold) return GateDecision.ESCALATE;
        return GateDecision.FAIL;
    }

    public Verdict lastVerdict() {
        return lastVerdict;
    }

    private double extractScore(Verdict verdict) {
        if (verdict.aggregated() == null || verdict.aggregated().score() == null) {
            return 0.0;
        }
        Score score = verdict.aggregated().score();
        if (score instanceof NumericalScore ns) {
            return ns.value();
        }
        return verdict.aggregated().pass() ? 1.0 : 0.0;
    }
}
