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
package io.github.markpollack.harness.flows;

import io.github.markpollack.harness.flows.steps.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.markpollack.harness.flows.AgentFlow.maxIterations;
import static io.github.markpollack.harness.flows.AgentFlow.until;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentFlowTest {

    private final AgentContext ctx = AgentContext.create();

    // -------------------------------------------------------------------------
    // sequential
    // -------------------------------------------------------------------------

    @Test
    void sequentialShouldExecuteSingleStep() {
        AgentStep<String, String> upper = Step.of((AgentContext c, String input) -> input.toUpperCase());
        AgentStep<String, String> flow = AgentFlow.sequential(upper);

        assertThat(flow.execute(ctx, "hello")).isEqualTo("HELLO");
    }

    @Test
    void sequentialShouldPassOutputOfEachStepAsInputToNext() {
        List<String> capturedInputs = new ArrayList<>();

        AgentStep<String, String> stepA = Step.of((AgentContext c, String input) -> {
            capturedInputs.add(input);
            return input + "-A";
        });
        AgentStep<String, String> stepB = Step.of((AgentContext c, String input) -> {
            capturedInputs.add(input);
            return input + "-B";
        });
        AgentStep<String, String> stepC = Step.of((AgentContext c, String input) -> {
            capturedInputs.add(input);
            return input + "-C";
        });

        AgentStep<String, String> flow = AgentFlow.sequential(stepA, stepB, stepC);

        String result = flow.execute(ctx, "start");

        assertThat(result).isEqualTo("start-A-B-C");
        assertThat(capturedInputs).containsExactly("start", "start-A", "start-A-B");
    }

    @Test
    void sequentialShouldPassContextToAllSteps() {
        AgentContext namedCtx = AgentContext.withRunId("test-run-42");
        List<String> capturedRunIds = new ArrayList<>();

        AgentStep<String, String> step1 = Step.of((AgentContext c, String input) -> {
            capturedRunIds.add(c.runId());
            return input;
        });
        AgentStep<String, String> step2 = Step.of((AgentContext c, String input) -> {
            capturedRunIds.add(c.runId());
            return input;
        });

        AgentFlow.sequential(step1, step2).execute(namedCtx, "input");

        assertThat(capturedRunIds).containsExactly("test-run-42", "test-run-42");
    }

    @Test
    void sequentialShouldThrowWhenNoSteps() {
        assertThatThrownBy(() -> AgentFlow.sequential())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one step");
    }

    @Test
    void sequentialFlowsCompose() {
        AgentStep<String, String> inner = AgentFlow.sequential(
                Step.of((AgentContext c, String s) -> s + "-inner")
        );
        AgentStep<String, String> outer = AgentFlow.sequential(
                inner,
                Step.of((AgentContext c, String s) -> s + "-outer")
        );

        assertThat(outer.execute(ctx, "x")).isEqualTo("x-inner-outer");
    }

    // -------------------------------------------------------------------------
    // parallel
    // -------------------------------------------------------------------------

    @Test
    void parallelShouldCollectAllResults() {
        AgentStep<String, String> stepA = Step.of((AgentContext c, String input) -> input + "-A");
        AgentStep<String, String> stepB = Step.of((AgentContext c, String input) -> input + "-B");
        AgentStep<String, String> stepC = Step.of((AgentContext c, String input) -> input + "-C");

        AgentFlow.ParallelFlow<String, String> flow = AgentFlow.parallel(stepA, stepB, stepC);

        List<String> results = flow.execute(ctx, "x");

        assertThat(results).hasSize(3);
        assertThat(results).containsExactlyInAnyOrder("x-A", "x-B", "x-C");
    }

    @Test
    void parallelShouldPassSameInputToAllSteps() {
        List<String> capturedInputs = new ArrayList<>();

        AgentStep<String, String> step1 = Step.of((AgentContext c, String input) -> {
            synchronized (capturedInputs) { capturedInputs.add(input); }
            return "r1";
        });
        AgentStep<String, String> step2 = Step.of((AgentContext c, String input) -> {
            synchronized (capturedInputs) { capturedInputs.add(input); }
            return "r2";
        });

        AgentFlow.parallel(step1, step2).execute(ctx, "shared-input");

        assertThat(capturedInputs).containsExactlyInAnyOrder("shared-input", "shared-input");
    }

    @Test
    void parallelWithAggregateShouldReduceResults() {
        AgentStep<String, String> s1 = Step.of((AgentContext c, String input) -> "part1");
        AgentStep<String, String> s2 = Step.of((AgentContext c, String input) -> "part2");
        AgentStep<String, String> s3 = Step.of((AgentContext c, String input) -> "part3");

        AgentStep<String, String> flow = AgentFlow.parallel(s1, s2, s3)
                .aggregate(results -> String.join(", ", results));

        String result = flow.execute(ctx, "any");

        assertThat(result).contains("part1").contains("part2").contains("part3");
    }

    @Test
    void parallelWithAggregateShouldBeComposableInSequential() {
        AgentStep<String, String> s1 = Step.of((AgentContext c, String input) -> "A");
        AgentStep<String, String> s2 = Step.of((AgentContext c, String input) -> "B");
        AgentStep<String, String> combined = AgentFlow.parallel(s1, s2)
                .aggregate(parts -> String.join("+", parts));
        AgentStep<String, String> appendResult = Step.of((AgentContext c, String s) -> s + "=result");

        AgentStep<String, String> flow = AgentFlow.sequential(combined, appendResult);

        String result = flow.execute(ctx, "ignored");

        assertThat(result).endsWith("=result");
        assertThat(result).contains("A").contains("B");
    }

    @Test
    void parallelShouldThrowWhenNoSteps() {
        assertThatThrownBy(() -> AgentFlow.parallel())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one step");
    }

    // -------------------------------------------------------------------------
    // loop
    // -------------------------------------------------------------------------

    @Test
    void loopShouldRepeatUntilConditionMet() {
        AtomicInteger callCount = new AtomicInteger(0);
        AgentStep<Integer, Integer> increment = Step.of((AgentContext c, Integer n) -> {
            callCount.incrementAndGet();
            return n + 1;
        });

        AgentStep<Integer, Integer> loop = AgentFlow.loop(
                increment,
                until(n -> n >= 5),
                maxIterations(10)
        );

        int result = loop.execute(ctx, 0);

        assertThat(result).isEqualTo(5);
        assertThat(callCount.get()).isEqualTo(5);
    }

    @Test
    void loopShouldReturnImmediatelyWhenConditionMetOnFirstIteration() {
        AtomicInteger callCount = new AtomicInteger(0);
        AgentStep<String, String> step = Step.of((AgentContext c, String s) -> {
            callCount.incrementAndGet();
            return "done";
        });

        AgentStep<String, String> loop = AgentFlow.loop(
                step,
                until(s -> s.equals("done")),
                maxIterations(5)
        );

        String result = loop.execute(ctx, "start");

        assertThat(result).isEqualTo("done");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void loopShouldThrowWhenMaxIterationsExceededWithoutConditionMet() {
        AgentStep<Integer, Integer> neverDone = Step.of((AgentContext c, Integer n) -> n + 1);

        AgentStep<Integer, Integer> loop = AgentFlow.loop(
                neverDone,
                until(n -> n > 100),
                maxIterations(3)
        );

        assertThatThrownBy(() -> loop.execute(ctx, 0))
                .isInstanceOf(MaxIterationsExceededException.class)
                .hasMessageContaining("3");
    }

    @Test
    void loopShouldPassPreviousOutputAsNextInput() {
        List<Integer> seen = new ArrayList<>();
        AgentStep<Integer, Integer> step = Step.of((AgentContext c, Integer n) -> {
            seen.add(n);
            return n * 2;
        });

        AgentStep<Integer, Integer> loop = AgentFlow.loop(
                step,
                until(n -> n >= 8),
                maxIterations(10)
        );

        int result = loop.execute(ctx, 1);

        // 1 → 2, 2 → 4, 4 → 8 (stops here)
        assertThat(seen).containsExactly(1, 2, 4);
        assertThat(result).isEqualTo(8);
    }

    // -------------------------------------------------------------------------
    // maxIterations validation
    // -------------------------------------------------------------------------

    @Test
    void maxIterationsShouldRejectZero() {
        assertThatThrownBy(() -> maxIterations(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxIterationsShouldRejectNegative() {
        assertThatThrownBy(() -> maxIterations(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // AgentContext propagation
    // -------------------------------------------------------------------------

    @Test
    void loopShouldPropagateContextToStep() {
        AgentContext namedCtx = AgentContext.withRunId("loop-test");
        List<String> runIds = new ArrayList<>();

        AgentStep<Integer, Integer> step = Step.of((AgentContext c, Integer n) -> {
            runIds.add(c.runId());
            return n + 1;
        });

        AgentFlow.loop(step, until(n -> n >= 3), maxIterations(5)).execute(namedCtx, 0);

        assertThat(runIds).allMatch(id -> id.equals("loop-test"));
        assertThat(runIds).hasSize(3);
    }
}
