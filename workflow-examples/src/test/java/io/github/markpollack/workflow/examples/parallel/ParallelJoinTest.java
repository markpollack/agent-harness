package io.github.markpollack.workflow.examples.parallel;

import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Examples of the two parallel join modes.
 *
 * <p>
 * {@code gather()} — homogeneous fan-out: all branches run concurrently and results are
 * collected into {@code List<Object>}, which becomes the input to the next step. Use when
 * all branches produce the same type and you need all results together.
 *
 * <p>
 * {@code parallel()} — enrichment join: each branch writes its output to a named context
 * key ({@code Steps.outputOf(branchName)}); the join passes the fork input through
 * unchanged. Use for heterogeneous branches where downstream reads from context keys.
 */
class ParallelJoinTest {

    @Test
    @SuppressWarnings("unchecked")
    void gather_fanOutAndJoinBothResults() {
        Step<String, String> reviewStep = Step.named("review", (ctx, in) -> "review:" + in);
        Step<String, String> auditStep  = Step.named("audit",  (ctx, in) -> "audit:"  + in);

        List<Object> result = (List<Object>) Workflow.<String, Object>define("parallel-review")
                .gather(reviewStep, auditStep)
                .run("pr-123");

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder("review:pr-123", "audit:pr-123");
    }

    @Test
    void parallel_executesAllBranches() {
        AtomicBoolean reviewRan = new AtomicBoolean(false);
        AtomicBoolean auditRan  = new AtomicBoolean(false);

        Step<String, String> reviewStep = Step.named("review", (ctx, in) -> {
            reviewRan.set(true);
            return "review-done";
        });
        Step<String, String> auditStep = Step.named("audit", (ctx, in) -> {
            auditRan.set(true);
            return "audit-done";
        });

        Workflow.<String, Object>define("parallel-review")
                .parallel(reviewStep, auditStep)
                .run("input");

        assertThat(reviewRan.get()).isTrue();
        assertThat(auditRan.get()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void gather_resultPassableToNextStep() {
        Step<String, String> reviewStep = Step.named("review", (ctx, in) -> "r");
        Step<String, String> auditStep  = Step.named("audit",  (ctx, in) -> "a");

        String summary = (String) Workflow.<String, Object>define("parallel-review")
                .gather(reviewStep, auditStep)
                .then(Step.named("summarize", (ctx, in) -> {
                    List<Object> parts = (List<Object>) in;
                    return String.join("+", parts.stream().map(Object::toString).toList());
                }))
                .run("input");

        assertThat(summary).isEqualTo("r+a");
    }

}
