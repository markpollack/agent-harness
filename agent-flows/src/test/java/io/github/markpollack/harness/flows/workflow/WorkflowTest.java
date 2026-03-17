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
package io.github.markpollack.harness.flows.workflow;

import io.github.markpollack.harness.flows.AgentContext;
import io.github.markpollack.harness.flows.Step;
import io.github.markpollack.harness.flows.steps.Steps;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTest {

    // -------------------------------------------------------------------------
    // Sequential
    // -------------------------------------------------------------------------

    @Nested
    class Sequential {

        @Test
        void twoStepSequentialShouldCompileCorrectGraph() {
            Step<String, String> a = Step.named("fetch", (ctx, in) -> ((String) in).toUpperCase());
            Step<String, String> b = Step.named("analyze", (ctx, in) -> in + "!");

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("test")
                    .step(a)
                    .step(b)
                    .compile();

            assertThat(graph.name()).isEqualTo("test");
            assertThat(graph.nodes()).hasSize(2);
            assertThat(graph.edges()).hasSize(1);
        }

        @Test
        void twoStepSequentialShouldExecute() {
            Step<String, String> a = Step.named("upper", (ctx, in) -> ((String) in).toUpperCase());
            Step<String, String> b = Step.named("exclaim", (ctx, in) -> in + "!");

            String result = Workflow.<String, String>define("test")
                    .step(a)
                    .then(b)
                    .run("hello");

            assertThat(result).isEqualTo("HELLO!");
        }

        @Test
        void threeStepChainShouldPassOutputAsInput() {
            String result = Workflow.<String, String>define("chain")
                    .step(Step.named("a", (ctx, in) -> in + "-A"))
                    .then(Step.named("b", (ctx, in) -> in + "-B"))
                    .then(Step.named("c", (ctx, in) -> in + "-C"))
                    .run("start");

            assertThat(result).isEqualTo("start-A-B-C");
        }

        @Test
        void thenShouldBeAliasForStep() {
            String result1 = Workflow.<String, String>define("v1")
                    .step(Step.named("a", (ctx, in) -> in + "1"))
                    .step(Step.named("b", (ctx, in) -> in + "2"))
                    .run("x");

            String result2 = Workflow.<String, String>define("v2")
                    .step(Step.named("a", (ctx, in) -> in + "1"))
                    .then(Step.named("b", (ctx, in) -> in + "2"))
                    .run("x");

            assertThat(result1).isEqualTo(result2);
        }
    }

    // -------------------------------------------------------------------------
    // Parallel
    // -------------------------------------------------------------------------

    @Nested
    class Parallel {

        @Test
        @SuppressWarnings("unchecked")
        void parallelShouldFanOutAndCollectResults() {
            Step<String, String> a = Step.named("a", (ctx, in) -> in + "-A");
            Step<String, String> b = Step.named("b", (ctx, in) -> in + "-B");

            List<Object> result = (List<Object>) Workflow.<String, Object>define("par")
                    .parallel(a, b)
                    .run("x");

            assertThat(result).containsExactlyInAnyOrder("x-A", "x-B");
        }
    }

    // -------------------------------------------------------------------------
    // RepeatUntil
    // -------------------------------------------------------------------------

    @Nested
    class RepeatUntil {

        @Test
        void repeatUntilShouldProduceLoopGraph() {
            WorkflowGraph<Integer, Integer> graph = Workflow.<Integer, Integer>define("loop")
                    .repeatUntil(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 3)
                        .step(Step.named("increment", (ctx, n) -> ((Integer) n) + 1))
                    .end()
                    .compile();

            assertThat(graph.nodes()).hasSize(1);
            assertThat(graph.nodes().get(0).name()).startsWith("loop-");
        }

        @Test
        void repeatUntilShouldExecuteCorrectNumberOfTimes() {
            AtomicInteger count = new AtomicInteger(0);

            int result = Workflow.<Integer, Integer>define("loop")
                    .repeatUntil(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 3)
                        .step(Step.named("increment", (ctx, n) -> {
                            count.incrementAndGet();
                            return ((Integer) n) + 1;
                        }))
                    .end()
                    .run(0);

            assertThat(result).isEqualTo(3);
            assertThat(count.get()).isEqualTo(3);
        }

        @Test
        void repeatUntilShouldExitImmediatelyWhenConditionMet() {
            AtomicInteger count = new AtomicInteger(0);

            int result = Workflow.<Integer, Integer>define("loop")
                    .repeatUntil(ctx -> true) // exit immediately
                        .step(Step.named("never", (ctx, n) -> {
                            count.incrementAndGet();
                            return n;
                        }))
                    .end()
                    .run(42);

            assertThat(result).isEqualTo(42);
            assertThat(count.get()).isEqualTo(0);
        }
    }

    // -------------------------------------------------------------------------
    // Branch
    // -------------------------------------------------------------------------

    @Nested
    class Branch {

        @Test
        void branchShouldRouteTrueToThenStep() {
            String result = Workflow.<String, String>define("branch-test")
                    .step(Step.named("start", (ctx, in) -> in))
                    .branch(output -> "high".equals(output))
                        .then(Step.named("high-path", (ctx, in) -> "HIGH: " + in))
                        .otherwise(Step.named("low-path", (ctx, in) -> "LOW: " + in))
                    .run("high");

            assertThat(result).isEqualTo("HIGH: high");
        }

        @Test
        void branchShouldRouteFalseToOtherwiseStep() {
            String result = Workflow.<String, String>define("branch-test")
                    .step(Step.named("start", (ctx, in) -> in))
                    .branch(output -> "high".equals(output))
                        .then(Step.named("high-path", (ctx, in) -> "HIGH: " + in))
                        .otherwise(Step.named("low-path", (ctx, in) -> "LOW: " + in))
                    .run("low");

            assertThat(result).isEqualTo("LOW: low");
        }
    }

    // -------------------------------------------------------------------------
    // onError
    // -------------------------------------------------------------------------

    @Nested
    class OnError {

        @Test
        void onErrorShouldRouteToRecoveryStep() {
            Step<String, String> risky = Step.named("risky", (ctx, in) -> {
                throw new IllegalArgumentException("bad input");
            });
            Step<String, String> recovery = Step.named("recovery", (ctx, in) -> "recovered");

            String result = Workflow.<String, String>define("error-test")
                    .step(risky)
                        .onError(IllegalArgumentException.class, recovery)
                    .run("test");

            assertThat(result).isEqualTo("recovered");
        }

        @Test
        void onErrorShouldProduceErrorEdgeInGraph() {
            Step<String, String> risky = Step.named("risky", (ctx, in) -> in);
            Step<String, String> recovery = Step.named("recovery", (ctx, in) -> in);

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("error-test")
                    .step(risky)
                        .onError(IllegalArgumentException.class, recovery)
                    .compile();

            // Should have normal node + recovery node + error edge
            assertThat(graph.nodes()).hasSize(2);
            assertThat(graph.edges().stream()
                    .anyMatch(e -> e.label() != null && e.label().startsWith("error:")))
                    .isTrue();
        }

        @Test
        void unmatchedExceptionShouldPropagate() {
            Step<String, String> risky = Step.named("risky", (ctx, in) -> {
                throw new UnsupportedOperationException("nope");
            });
            Step<String, String> recovery = Step.named("recovery", (ctx, in) -> "recovered");

            assertThatThrownBy(() -> Workflow.<String, String>define("error-test")
                    .step(risky)
                        .onError(IllegalArgumentException.class, recovery)
                    .run("test"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Steps.terminate
    // -------------------------------------------------------------------------

    @Nested
    class Terminate {

        @Test
        void terminateShouldEndWorkflow() {
            String result = Workflow.<String, String>define("term-test")
                    .step(Steps.terminate(WorkflowStatus.FAILED, "invalid input"))
                    .run("test");

            // Terminated workflows return null
            assertThat(result).isNull();
        }

        @Test
        void terminateShouldProduceNamedNode() {
            Step<String, String> termStep = Steps.terminate(WorkflowStatus.FAILED, "done");
            assertThat(termStep.name()).contains("terminate").contains("FAILED");
        }
    }

    // -------------------------------------------------------------------------
    // Workflow implements Step
    // -------------------------------------------------------------------------

    @Nested
    class WorkflowAsStep {

        @Test
        void workflowShouldBeUsableAsStep() {
            Workflow<String, String> subWorkflow = Workflow.<String, String>define("inner")
                    .step(Step.named("upper", (ctx, in) -> ((String) in).toUpperCase()))
                    .build();

            String result = Workflow.<String, String>define("outer")
                    .step(subWorkflow)
                    .then(Step.named("exclaim", (ctx, in) -> in + "!"))
                    .run("hello");

            assertThat(result).isEqualTo("HELLO!");
        }

        @Test
        void workflowNameShouldComeFromGraph() {
            Workflow<String, String> workflow = Workflow.<String, String>define("my-workflow")
                    .step(Step.named("a", (ctx, in) -> in))
                    .build();

            assertThat(workflow.name()).isEqualTo("my-workflow");
        }
    }

    // -------------------------------------------------------------------------
    // RunOptions
    // -------------------------------------------------------------------------

    @Nested
    class RunOptionsTest {

        @Test
        void maxCostShouldCreateRunOptions() {
            RunOptions opts = RunOptions.maxCost(5.0);
            assertThat(opts.maxCostUsd()).isEqualTo(5.0);
            assertThat(opts.maxIterations()).isEqualTo(0);
            assertThat(opts.maxDuration()).isNull();
        }

        @Test
        void chainingBuildersShouldCombineConstraints() {
            RunOptions opts = RunOptions.maxCost(5.0).withMaxIterations(10);
            assertThat(opts.maxCostUsd()).isEqualTo(5.0);
            assertThat(opts.maxIterations()).isEqualTo(10);
        }

        @Test
        void runWithOptionsShouldAcceptRunOptions() {
            String result = Workflow.<String, String>define("test")
                    .step(Step.named("echo", (ctx, in) -> in))
                    .run("hello", RunOptions.unlimited());

            assertThat(result).isEqualTo("hello");
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    class EdgeCases {

        @Test
        void compileShouldThrowWhenNoSteps() {
            assertThatThrownBy(() -> Workflow.<String, String>define("empty").compile())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no steps");
        }

        @Test
        void singleStepWorkflowShouldWork() {
            String result = Workflow.<String, String>define("single")
                    .step(Step.named("only", (ctx, in) -> ((String) in).toUpperCase()))
                    .run("hello");

            assertThat(result).isEqualTo("HELLO");
        }
    }
}
