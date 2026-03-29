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
package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.steps.Steps;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

        @Test
        @SuppressWarnings("unchecked")
        void nestedParallelShouldCollectResultsCorrectly() {
            // Inner parallel workflows — each returns List<Object>
            Workflow<String, Object> innerAB = Workflow.<String, Object>define("inner-ab")
                    .parallel(
                            Step.named("a", (ctx, in) -> in + "-A"),
                            Step.named("b", (ctx, in) -> in + "-B"))
                    .build();

            Workflow<String, Object> innerCD = Workflow.<String, Object>define("inner-cd")
                    .parallel(
                            Step.named("c", (ctx, in) -> in + "-C"),
                            Step.named("d", (ctx, in) -> in + "-D"))
                    .build();

            // Outer parallel runs both inner parallels concurrently
            List<Object> result = (List<Object>) Workflow.<String, Object>define("outer")
                    .parallel(innerAB, innerCD)
                    .run("x");

            // Outer collects two List<Object> results — one from each inner parallel
            assertThat(result).hasSize(2);
            List<Object> ab = (List<Object>) result.get(0);
            List<Object> cd = (List<Object>) result.get(1);
            assertThat(ab).containsExactlyInAnyOrder("x-A", "x-B");
            assertThat(cd).containsExactlyInAnyOrder("x-C", "x-D");
        }
    }

    // -------------------------------------------------------------------------
    // RepeatUntil
    // -------------------------------------------------------------------------

    @Nested
    class RepeatUntil {

        @Test
        void repeatUntilShouldProduceExplodedLoopGraph() {
            WorkflowGraph<Integer, Integer> graph = Workflow.<Integer, Integer>define("loop")
                    .repeatUntil(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 3)
                        .step(Step.named("increment", (ctx, n) -> ((Integer) n) + 1))
                    .end()
                    .compile();

            // Exploded: LoopEntryNode + StepNode + LoopExitNode
            assertThat(graph.nodes()).hasSize(3);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.LoopEntryNode.class);
            assertThat(graph.nodes().get(1)).isInstanceOf(WorkflowNode.StepNode.class);
            assertThat(graph.nodes().get(2)).isInstanceOf(WorkflowNode.LoopExitNode.class);
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
    // RepeatUntilOutput (do-while)
    // -------------------------------------------------------------------------

    @Nested
    class RepeatUntilOutput {

        @Test
        void repeatUntilOutputShouldProduceDoWhileTopology() {
            WorkflowGraph<Integer, Integer> graph = Workflow.<Integer, Integer>define("do-while")
                    .repeatUntilOutput(n -> ((Integer) n) >= 3)
                        .step(Step.named("increment", (ctx, n) -> ((Integer) n) + 1))
                    .end()
                    .compile();

            // Do-while: StepNode + LoopCheckNode + LoopExitNode
            assertThat(graph.nodes()).hasSize(3);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.StepNode.class);
            assertThat(graph.nodes().get(1)).isInstanceOf(WorkflowNode.LoopCheckNode.class);
            assertThat(graph.nodes().get(2)).isInstanceOf(WorkflowNode.LoopExitNode.class);
        }

        @Test
        void repeatUntilOutputShouldRunBodyThenCheckOutput() {
            AtomicInteger count = new AtomicInteger(0);

            int result = Workflow.<Integer, Integer>define("do-while")
                    .repeatUntilOutput(n -> ((Integer) n) >= 3)
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
        void repeatUntilOutputBodyShouldAlwaysRunAtLeastOnce() {
            AtomicInteger count = new AtomicInteger(0);

            String result = Workflow.<String, String>define("do-while-once")
                    .repeatUntilOutput(s -> true)  // exit immediately after first body run
                        .step(Step.named("once", (ctx, in) -> {
                            count.incrementAndGet();
                            return in + "-ran";
                        }))
                    .end()
                    .run("start");

            assertThat(result).isEqualTo("start-ran");
            assertThat(count.get()).isEqualTo(1);  // body ALWAYS runs at least once (do-while)
        }
    }

    // -------------------------------------------------------------------------
    // Topology tests (exploded graph structure)
    // -------------------------------------------------------------------------

    @Nested
    class Topology {

        @Test
        void branchShouldProduceGatewayAndJoinNodes() {
            WorkflowGraph<String, String> graph = Workflow.<String, String>define("branch-topo")
                    .branch(output -> true)
                        .then(Step.named("a", (ctx, in) -> in))
                        .otherwise(Step.named("b", (ctx, in) -> in))
                    .compile();

            // GatewayNode + StepNode(a) + StepNode(b) + JoinNode = 4
            assertThat(graph.nodes()).hasSize(4);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.GatewayNode.class);
            assertThat(graph.nodes().get(1)).isInstanceOf(WorkflowNode.StepNode.class);
            assertThat(graph.nodes().get(2)).isInstanceOf(WorkflowNode.StepNode.class);
            assertThat(graph.nodes().get(3)).isInstanceOf(WorkflowNode.JoinNode.class);
            // 5 edges: prev→gw, gw→a(true), gw→b(false), a→join, b→join
            assertThat(graph.edges()).hasSize(4); // no prev edge since branch is first
        }

        @Test
        void parallelShouldProduceForkAndJoinNodes() {
            WorkflowGraph<String, Object> graph = Workflow.<String, Object>define("par-topo")
                    .parallel(
                            Step.named("a", (ctx, in) -> in),
                            Step.named("b", (ctx, in) -> in))
                    .compile();

            // ForkNode + StepNode(a) + StepNode(b) + JoinNode = 4
            assertThat(graph.nodes()).hasSize(4);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.ForkNode.class);
            assertThat(graph.nodes().get(3)).isInstanceOf(WorkflowNode.JoinNode.class);
            // 4 edges: fork→a(BranchIndex0), fork→b(BranchIndex1), a→join, b→join
            assertThat(graph.edges()).hasSize(4);
        }

        @Test
        void decisionShouldProduceDecisionAndJoinNodes() {
            var client = org.mockito.Mockito.mock(
                    org.springframework.ai.chat.client.ChatClient.class,
                    org.mockito.Mockito.RETURNS_DEEP_STUBS);

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("dec-topo")
                    .decision(client)
                        .option("x", Step.named("sx", (ctx, in) -> in))
                        .option("y", Step.named("sy", (ctx, in) -> in))
                    .end()
                    .compile();

            // DecisionNode + StepNode(sx) + StepNode(sy) + JoinNode = 4
            assertThat(graph.nodes()).hasSize(4);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.DecisionNode.class);
            assertThat(graph.nodes().get(3)).isInstanceOf(WorkflowNode.JoinNode.class);
        }
    }

    // -------------------------------------------------------------------------
    // Context auto-propagation (DD-15)
    // -------------------------------------------------------------------------

    @Nested
    class ContextPropagation {

        @Test
        void downstreamStepShouldReadPriorStepOutputFromContext() {
            AtomicReference<Object> captured = new AtomicReference<>();

            Workflow.<String, String>define("propagation")
                    .step(Step.named("produce", (ctx, in) -> "produced-value"))
                    .then(Step.named("middle", (ctx, in) -> in + "-middle"))
                    .then(Step.named("consume", (ctx, in) -> {
                        // Read step A's output even though step B sits between them
                        captured.set(ctx.get(Steps.outputOf("produce")).orElse(null));
                        return in;
                    }))
                    .run("start");

            assertThat(captured.get()).isEqualTo("produced-value");
        }

        @Test
        void eachStepOutputShouldBeAccessibleByName() {
            AtomicReference<Object> capturedA = new AtomicReference<>();
            AtomicReference<Object> capturedB = new AtomicReference<>();

            Workflow.<String, String>define("multi-propagation")
                    .step(Step.named("step-a", (ctx, in) -> "A"))
                    .then(Step.named("step-b", (ctx, in) -> "B"))
                    .then(Step.named("reader", (ctx, in) -> {
                        capturedA.set(ctx.get(Steps.outputOf("step-a")).orElse(null));
                        capturedB.set(ctx.get(Steps.outputOf("step-b")).orElse(null));
                        return "done";
                    }))
                    .run("start");

            assertThat(capturedA.get()).isEqualTo("A");
            assertThat(capturedB.get()).isEqualTo("B");
        }
    }

    // -------------------------------------------------------------------------
    // Step Parameterization
    // -------------------------------------------------------------------------

    @Nested
    class StepParameterization {

        // -- Pattern 1: Constructor injection --

        static class MultiplyStep implements Step<Object, Object> {
            private final int factor;
            MultiplyStep(int factor) { this.factor = factor; }
            @Override public String name() { return "multiply-by-" + factor; }
            @Override public Object execute(AgentContext ctx, Object input) {
                return ((Integer) input) * factor;
            }
        }

        @Test
        void constructorInjectionShouldParameterizeStep() {
            Step<Object, Object> doubler = new MultiplyStep(2);
            Step<Object, Object> tripler = new MultiplyStep(3);

            int doubled = (int) Workflow.<Integer, Object>define("double")
                    .step(doubler).run(5);
            int tripled = (int) Workflow.<Integer, Object>define("triple")
                    .step(tripler).run(5);

            assertThat(doubled).isEqualTo(10);
            assertThat(tripled).isEqualTo(15);
        }

        @Test
        void constructorInjectedStepShouldHaveDescriptiveName() {
            Step<Object, Object> step = new MultiplyStep(7);
            assertThat(step.name()).isEqualTo("multiply-by-7");
        }

        // -- Pattern 2: Input chaining --

        @Test
        void inputChainingShouldPassOutputAsInput() {
            String result = Workflow.<String, String>define("chain")
                    .step(Step.named("a", (ctx, in) -> in + "-A"))
                    .then(Step.named("b", (ctx, in) -> in + "-B"))
                    .then(Step.named("c", (ctx, in) -> in + "-C"))
                    .run("start");

            assertThat(result).isEqualTo("start-A-B-C");
        }

        // -- Pattern 3: Context auto-propagation (Steps.outputOf) --

        @Test
        void downstreamStepShouldReadNonAdjacentStepOutput() {
            AtomicReference<Object> capturedFromA = new AtomicReference<>();

            Workflow.<String, String>define("context-read")
                    .step(Step.named("step-a", (ctx, in) -> "value-from-A"))
                    .then(Step.named("step-b", (ctx, in) -> "value-from-B"))
                    .then(Step.named("step-c", (ctx, in) -> {
                        capturedFromA.set(ctx.get(Steps.outputOf("step-a")).orElse(null));
                        return in;
                    }))
                    .run("start");

            assertThat(capturedFromA.get()).isEqualTo("value-from-A");
        }

        // -- Pattern 3c: Multi-output via record --

        record ClassificationResult(String category, double confidence) {}

        @Test
        void multiOutputStepShouldBeReadableFromContext() {
            AtomicReference<Object> capturedRecord = new AtomicReference<>();

            Workflow.<String, Object>define("multi-output")
                    .step(Step.named("classify", (ctx, in) ->
                            new ClassificationResult("medical", 0.95)))
                    .then(Step.named("route", (ctx, in) -> {
                        ClassificationResult r = (ClassificationResult) in;
                        return "routed to " + r.category();
                    }))
                    .then(Step.named("audit", (ctx, in) -> {
                        capturedRecord.set(ctx.get(Steps.outputOf("classify")).orElse(null));
                        return in;
                    }))
                    .run("I broke my leg");

            assertThat(capturedRecord.get()).isInstanceOf(ClassificationResult.class);
            ClassificationResult result = (ClassificationResult) capturedRecord.get();
            assertThat(result.category()).isEqualTo("medical");
            assertThat(result.confidence()).isEqualTo(0.95);
        }

        @Test
        void multiOutputRecordFieldsShouldBeAccessibleViaInputChaining() {
            String result = (String) Workflow.<String, Object>define("record-chain")
                    .step(Step.named("produce", (ctx, in) ->
                            new ClassificationResult("legal", 0.8)))
                    .then(Step.named("consume", (ctx, in) -> {
                        ClassificationResult r = (ClassificationResult) in;
                        return r.category() + " at " + r.confidence();
                    }))
                    .run("contract dispute");

            assertThat(result).isEqualTo("legal at 0.8");
        }

        // -- Pattern 4: Mixed (constructor + input + context) --

        static class ThresholdCheckStep implements Step<Object, Object> {
            private final double threshold;
            ThresholdCheckStep(double threshold) { this.threshold = threshold; }
            @Override public String name() { return "check-threshold-" + threshold; }
            @Override public Object execute(AgentContext ctx, Object input) {
                double value = (Double) input;
                int iteration = ctx.get(AgentContext.ITERATION_COUNT).orElse(-1);
                return "value=" + value + " threshold=" + threshold
                        + " pass=" + (value >= threshold) + " iteration=" + iteration;
            }
        }

        @Test
        void mixedParameterizationShouldCombineAllSources() {
            String result = (String) Workflow.<Double, Object>define("mixed")
                    .step(new ThresholdCheckStep(0.7))
                    .run(0.85);

            assertThat(result).contains("value=0.85");
            assertThat(result).contains("threshold=0.7");
            assertThat(result).contains("pass=true");
        }

        // -- All step outputs accessible --

        @Test
        void allPriorStepOutputsShouldBeInContext() {
            AtomicReference<Object> fromA = new AtomicReference<>();
            AtomicReference<Object> fromB = new AtomicReference<>();
            AtomicReference<Object> fromC = new AtomicReference<>();

            Workflow.<String, String>define("all-outputs")
                    .step(Step.named("a", (ctx, in) -> "A-out"))
                    .then(Step.named("b", (ctx, in) -> "B-out"))
                    .then(Step.named("c", (ctx, in) -> "C-out"))
                    .then(Step.named("reader", (ctx, in) -> {
                        fromA.set(ctx.get(Steps.outputOf("a")).orElse(null));
                        fromB.set(ctx.get(Steps.outputOf("b")).orElse(null));
                        fromC.set(ctx.get(Steps.outputOf("c")).orElse(null));
                        return in;
                    }))
                    .run("start");

            assertThat(fromA.get()).isEqualTo("A-out");
            assertThat(fromB.get()).isEqualTo("B-out");
            assertThat(fromC.get()).isEqualTo("C-out");
        }
    }

    // -------------------------------------------------------------------------
    // WorkflowGraphAssert — type compatibility checking
    // -------------------------------------------------------------------------

    @Nested
    class TypeCompatibility {

        // Typed steps — override inputType/outputType
        static class StringToInt implements Step<String, Integer> {
            @Override public String name() { return "string-to-int"; }
            @Override public Integer execute(AgentContext ctx, String input) { return input.length(); }
            @Override public Class<?> inputType() { return String.class; }
            @Override public Class<?> outputType() { return Integer.class; }
        }

        static class IntToDouble implements Step<Integer, Double> {
            @Override public String name() { return "int-to-double"; }
            @Override public Double execute(AgentContext ctx, Integer input) { return input * 1.5; }
            @Override public Class<?> inputType() { return Integer.class; }
            @Override public Class<?> outputType() { return Double.class; }
        }

        static class DoubleToString implements Step<Double, String> {
            @Override public String name() { return "double-to-string"; }
            @Override public String execute(AgentContext ctx, Double input) { return "result: " + input; }
            @Override public Class<?> inputType() { return Double.class; }
            @Override public Class<?> outputType() { return String.class; }
        }

        static class StringToString implements Step<String, String> {
            @Override public String name() { return "string-to-string"; }
            @Override public String execute(AgentContext ctx, String input) { return input.toUpperCase(); }
            @Override public Class<?> inputType() { return String.class; }
            @Override public Class<?> outputType() { return String.class; }
        }

        @Test
        void compatibleChainShouldPass() {
            WorkflowGraph<String, Object> graph = Workflow.<String, Object>define("compatible")
                    .step(new StringToInt())
                    .then(new IntToDouble())
                    .then(new DoubleToString())
                    .compile();

            WorkflowGraphAssert.assertTypeCompatible(graph);
            // no exception
        }

        @Test
        void incompatibleChainShouldThrow() {
            WorkflowGraph<String, Object> graph = Workflow.<String, Object>define("incompatible")
                    .step(new StringToInt())      // outputs Integer
                    .then(new DoubleToString())    // expects Double — MISMATCH
                    .compile();

            assertThatThrownBy(() -> WorkflowGraphAssert.assertTypeCompatible(graph))
                    .isInstanceOf(WorkflowGraphAssert.TypeIncompatibleException.class)
                    .hasMessageContaining("Integer")
                    .hasMessageContaining("Double");
        }

        @Test
        void untypedStepsShouldBeSkipped() {
            // Lambda steps return Object.class for both types — skipped
            WorkflowGraph<String, Object> graph = Workflow.<String, Object>define("mixed")
                    .step(new StringToInt())
                    .then(Step.named("untyped", (ctx, in) -> in))  // Object → Object, skipped
                    .then(new DoubleToString())
                    .compile();

            // No exception — the untyped step in the middle breaks the chain
            // but assertTypeCompatible skips it
            WorkflowGraphAssert.assertTypeCompatible(graph);
        }

        @Test
        void compatibleChainShouldAlsoExecuteCorrectly() {
            String result = (String) Workflow.<String, Object>define("typed-pipeline")
                    .step(new StringToInt())
                    .then(new IntToDouble())
                    .then(new DoubleToString())
                    .run("hello");

            assertThat(result).isEqualTo("result: 7.5"); // "hello".length()=5, 5*1.5=7.5
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
