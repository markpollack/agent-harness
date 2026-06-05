package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.score.NumericalScore;
import io.github.markpollack.judge.score.Score;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Quality gate backed by {@code spring-ai-agents-judge} Jury.
 * <p>
 * PASS if the aggregated score >= threshold, FAIL otherwise.
 * On FAIL, the {@link Verdict} is available via {@link #lastVerdict()} so the
 * executor can write it into {@link AgentContext#JUDGE_VERDICT} for the retry step.
 *
 * @param <O> the type of output being evaluated
 */
public class JudgeGate<O> implements Gate<O> {

    private final Jury jury;
    private final double threshold;
    private volatile Verdict lastVerdict;

    public JudgeGate(Jury jury, double threshold) {
        this.jury = Objects.requireNonNull(jury);
        this.threshold = threshold;
    }

    @Override
    public GateDecision evaluate(AgentContext ctx, O output) {
        JudgmentContext judgmentCtx = JudgmentContext.builder()
                .goal("Gate evaluation")
                .agentOutput(output != null ? output.toString() : "")
                .executionTime(Duration.ZERO)
                .startedAt(Instant.now())
                .build();

        lastVerdict = jury.vote(judgmentCtx);
        double score = extractScore(lastVerdict);
        return score >= threshold ? GateDecision.PASS : GateDecision.FAIL;
    }

    /** Returns the verdict from the most recent evaluation, or null if not yet evaluated. */
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
        // BooleanScore: treat pass as 1.0, fail as 0.0
        return verdict.aggregated().pass() ? 1.0 : 0.0;
    }
}
