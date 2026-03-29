/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.examples.lc4j;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transliteration of LangChain4j's {@code sequence(write, loopBuilder(score, edit).until(score >= 0.8))} pattern.
 *
 * <h2>LC4j equivalent (collapsed)</h2>
 * <pre>
 * Workflow<String, String> editLoop = loopBuilder()
 *     .first(score)
 *     .then(edit)
 *     .until(ctx -> ctx.score() >= 0.8)
 *     .build();
 *
 * Workflow<String, String> pipeline = sequenceBuilder()
 *     .first(write)
 *     .then(editLoop)
 *     .build();
 * pipeline.execute(topic);
 * </pre>
 *
 * <h2>Our DSL equivalent</h2>
 * <pre>
 * // Inner loop as a standalone Workflow — implements Step for composition
 * Workflow<String, String> editLoop = Workflow.define("edit-loop")
 *     .repeatUntil(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 4)
 *         .step(score)
 *         .step(edit)
 *     .end()
 *     .build();
 *
 * // Outer sequence — editLoop drops in as a plain Step
 * Workflow.define("styled-writer")
 *     .step(write)
 *     .then(editLoop)
 *     .run(topic);
 * </pre>
 *
 * The loop exits at ITERATION_COUNT >= 4, simulating a quality score that crosses
 * 0.8 after 4 edit cycles (0.2 increments per cycle).
 * The key demonstration: {@code Workflow implements Step} means the inner loop
 * composes into the outer sequence without adapters.
 */
class StyledWriterWorkflowTest {

    @Test
    void loopShouldIterateCorrectNumberOfTimes() {
        // Score improves 0.2 per iteration; exit when count >= 4 (score would reach 0.8)
        AtomicInteger loopBodyInvocations = new AtomicInteger(0);

        Step<String, String> scoreStep = Step.named("score", (ctx, in) -> {
            loopBodyInvocations.incrementAndGet();
            int iteration = ctx.get(AgentContext.ITERATION_COUNT).orElse(0);
            return in + "[scored@" + iteration + "]";
        });
        Step<String, String> editStep = Step.named("edit", (ctx, in) -> in + "[edited]");

        // Inner loop — simulate: exit when score >= 0.8 (i.e., after 4 cycles at 0.2/cycle)
        Workflow<String, String> editLoop = Workflow.<String, String>define("edit-loop")
                .repeatUntil(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 4)
                    .step(scoreStep)
                    .step(editStep)
                .end()
                .build();

        // Outer sequence — Workflow implements Step, no adapters
        String result = Workflow.<String, String>define("styled-writer")
                .step(Step.named("write", (ctx, in) -> in + "[written]"))
                .then(editLoop)
                .run("topic");

        // score runs 4 times (iterations 0, 1, 2, 3 all pass; exit at 4)
        assertThat(loopBodyInvocations.get()).isEqualTo(4);
        assertThat(result).contains("[written]").contains("[scored@0]").contains("[edited]");
    }

    @Test
    void iterationCountShouldIncrementAcrossLoopCycles() {
        // Track ITERATION_COUNT values seen by the body step
        AtomicInteger maxIterationSeen = new AtomicInteger(-1);

        Step<String, String> bodyStep = Step.named("body", (ctx, in) -> {
            int count = ctx.get(AgentContext.ITERATION_COUNT).orElse(-1);
            maxIterationSeen.set(Math.max(maxIterationSeen.get(), count));
            return in + count;
        });

        Workflow.<String, String>define("count-test")
                .repeatUntil(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 3)
                    .step(bodyStep)
                .end()
                .run("start");

        // Body ran at iterations 0, 1, 2 — max seen is 2
        assertThat(maxIterationSeen.get()).isEqualTo(2);
    }

    @Test
    void loopExitsImmediatelyWhenConditionAlreadyMet() {
        AtomicInteger invocations = new AtomicInteger(0);

        int result = Workflow.<Integer, Integer>define("immediate-exit")
                .repeatUntil(ctx -> true)  // always exit
                    .step(Step.named("never-runs", (ctx, n) -> {
                        invocations.incrementAndGet();
                        return (Integer) n + 1;
                    }))
                .end()
                .run(42);

        assertThat(invocations.get()).isEqualTo(0);
        assertThat(result).isEqualTo(42);
    }
}
