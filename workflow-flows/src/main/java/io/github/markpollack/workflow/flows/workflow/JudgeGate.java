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
import java.util.function.BiFunction;

/**
 * Quality gate backed by an {@code agent-judge} {@link Jury}.
 * <p>
 * PASS if the aggregated score &gt;= threshold, FAIL otherwise.
 * On FAIL, the {@link Verdict} is available via {@link #lastVerdict()} so the
 * executor can write it into {@link AgentContext#JUDGE_VERDICT} for the retry step.
 *
 * <h2>Building the {@link JudgmentContext}</h2>
 * The jury votes on a {@link JudgmentContext} produced by a <em>context mapper</em> —
 * a {@code (AgentContext, O) -> JudgmentContext} function supplied <strong>at
 * construction (required)</strong>. This is deliberate: real judges read their typed
 * inputs from {@link JudgmentContext#metadata()} and/or {@link JudgmentContext#workspace()}
 * (build / coverage / quality judges all do), so the gate cannot build a usable context
 * generically — only the caller knows how to map the workflow {@link AgentContext} and the
 * gate output into the judge's inputs. Callers whose judges genuinely read nothing but
 * {@code agentOutput} may pass {@link #defaultContextMapper(String)} explicitly.
 *
 * @param <O> the type of output being evaluated
 */
public class JudgeGate<O> implements Gate<O> {

    private final Jury jury;
    private final double threshold;
    private final BiFunction<AgentContext, O, JudgmentContext> contextMapper;
    private volatile Verdict lastVerdict;

    /**
     * @param jury          the jury that votes on the mapped context
     * @param threshold     PASS if the aggregated score &gt;= this value
     * @param contextMapper maps {@code (AgentContext, output)} to the {@link JudgmentContext} the jury
     *                      votes on — populate {@code metadata}/{@code workspace}/{@code status} here so
     *                      the judges receive their typed inputs
     */
    public JudgeGate(Jury jury, double threshold, BiFunction<AgentContext, O, JudgmentContext> contextMapper) {
        this.jury = Objects.requireNonNull(jury, "jury");
        this.threshold = threshold;
        this.contextMapper = Objects.requireNonNull(contextMapper, "contextMapper");
    }

    @Override
    public GateDecision evaluate(AgentContext ctx, O output) {
        JudgmentContext judgmentCtx = this.contextMapper.apply(ctx, output);
        lastVerdict = jury.vote(judgmentCtx);
        double score = extractScore(lastVerdict);
        return score >= threshold ? GateDecision.PASS : GateDecision.FAIL;
    }

    /** Returns the verdict from the most recent evaluation, or null if not yet evaluated. */
    public Verdict lastVerdict() {
        return lastVerdict;
    }

    /**
     * A minimal context mapper that sets only {@code goal} and {@code agentOutput = output.toString()}
     * (with {@code executionTime = ZERO}, {@code startedAt = now()}); it populates no metadata or
     * workspace. Use only when every judge in the jury reads nothing but {@code agentOutput}. Shared
     * with {@link TieredGate}.
     *
     * @param goal the {@link JudgmentContext#goal()} label
     * @param <O>  the type of output being evaluated
     */
    public static <O> BiFunction<AgentContext, O, JudgmentContext> defaultContextMapper(String goal) {
        return (ctx, output) -> JudgmentContext.builder()
                .goal(goal)
                .agentOutput(output != null ? output.toString() : "")
                .executionTime(Duration.ZERO)
                .startedAt(Instant.now())
                .build();
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
