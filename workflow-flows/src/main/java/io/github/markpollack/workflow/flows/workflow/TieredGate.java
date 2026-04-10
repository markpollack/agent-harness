package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import org.springaicommunity.judge.jury.Jury;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.score.NumericalScore;
import org.springaicommunity.judge.score.Score;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.springaicommunity.judge.context.JudgmentContext;

/**
 * Three-tier quality gate: PASS if score >= highThreshold, ESCALATE if >= lowThreshold, FAIL otherwise.
 * <p>
 * Like {@link JudgeGate}, backed by a {@code spring-ai-agents-judge} Jury.
 * The ESCALATE path is for borderline results that need human review.
 *
 * @param <O> the type of output being evaluated
 */
public class TieredGate<O> implements Gate<O> {

    private final Jury jury;
    private final double highThreshold;
    private final double lowThreshold;
    private volatile Verdict lastVerdict;

    public TieredGate(Jury jury, double highThreshold, double lowThreshold) {
        this.jury = Objects.requireNonNull(jury);
        if (lowThreshold > highThreshold) {
            throw new IllegalArgumentException(
                    "lowThreshold (" + lowThreshold + ") must be <= highThreshold (" + highThreshold + ")");
        }
        this.highThreshold = highThreshold;
        this.lowThreshold = lowThreshold;
    }

    @Override
    public GateDecision evaluate(AgentContext ctx, O output) {
        JudgmentContext judgmentCtx = JudgmentContext.builder()
                .goal("Tiered gate evaluation")
                .agentOutput(output != null ? output.toString() : "")
                .executionTime(Duration.ZERO)
                .startedAt(Instant.now())
                .build();

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
