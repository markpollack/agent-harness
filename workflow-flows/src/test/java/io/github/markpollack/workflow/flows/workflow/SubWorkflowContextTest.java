package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that sub-workflow context writes propagate back to the parent workflow,
 * and that parallel branch context writes are merged at the join node.
 */
class SubWorkflowContextTest {

    // =========================================================================
    // Sub-workflow context propagation (sequential nesting)
    // =========================================================================

    @Nested
    class SubWorkflowContextPropagation {

        static final ContextKey<String> STEP_A_KEY = ContextKey.of("sub.stepA", String.class);
        static final ContextKey<String> STEP_B_KEY = ContextKey.of("sub.stepB", String.class);
        static final ContextKey<String> DEEP_KEY = ContextKey.of("deep.key", String.class);

        Step<String, String> stepWithContextA = new Step<>() {
            @Override public String name() { return "step-a"; }
            @Override public String execute(AgentContext ctx, String input) { return "a:" + input; }
            @Override public AgentContext updateContext(AgentContext ctx, String output) {
                return ctx.mutate().with(STEP_A_KEY, "from-step-a").build();
            }
        };

        Step<String, String> stepWithContextB = new Step<>() {
            @Override public String name() { return "step-b"; }
            @Override public String execute(AgentContext ctx, String input) { return "b:" + input; }
            @Override public AgentContext updateContext(AgentContext ctx, String output) {
                return ctx.mutate().with(STEP_B_KEY, "from-step-b").build();
            }
        };

        @Test
        void subWorkflowContextWritesShouldBeVisibleInParentWorkflow() {
            AtomicReference<String> capturedA = new AtomicReference<>();
            AtomicReference<String> capturedB = new AtomicReference<>();

            // Build a sub-workflow that writes two context keys
            Workflow<String, String> subWorkflow = Workflow.<String, String>define("sub")
                    .step(stepWithContextA)
                    .then(stepWithContextB)
                    .build();

            // Parent workflow runs sub-workflow as a step, then reads the context keys
            Workflow.<String, String>define("parent")
                    .step(subWorkflow)
                    .then(Step.named("reader", (ctx, in) -> {
                        capturedA.set(ctx.get(STEP_A_KEY).orElse("missing"));
                        capturedB.set(ctx.get(STEP_B_KEY).orElse("missing"));
                        return in;
                    }))
                    .run("input");

            assertThat(capturedA.get()).isEqualTo("from-step-a");
            assertThat(capturedB.get()).isEqualTo("from-step-b");
        }

        @Test
        void triplyNestedSubWorkflowContextShouldPropagate() {
            Step<String, String> deepStep = new Step<>() {
                @Override public String name() { return "deep-step"; }
                @Override public String execute(AgentContext ctx, String input) { return input; }
                @Override public AgentContext updateContext(AgentContext ctx, String output) {
                    return ctx.mutate().with(DEEP_KEY, "deep-value").build();
                }
            };

            Workflow<String, String> inner = Workflow.<String, String>define("inner")
                    .step(deepStep)
                    .build();

            Workflow<String, String> middle = Workflow.<String, String>define("middle")
                    .step(inner)
                    .build();

            AtomicReference<String> captured = new AtomicReference<>();
            Workflow.<String, String>define("outer")
                    .step(middle)
                    .then(Step.named("reader", (ctx, in) -> {
                        captured.set(ctx.get(DEEP_KEY).orElse("missing"));
                        return in;
                    }))
                    .run("input");

            assertThat(captured.get()).isEqualTo("deep-value");
        }
    }

    // =========================================================================
    // Sub-workflow inside gate paths
    // =========================================================================

    @Nested
    class SubWorkflowInGatePath {

        static final ContextKey<String> GATE_KEY = ContextKey.of("gate.result", String.class);

        @Test
        void subWorkflowInGatePassPathShouldPropagateContext() {
            Step<String, String> passingStep = new Step<>() {
                @Override public String name() { return "pass-step"; }
                @Override public String execute(AgentContext ctx, String in) { return "passed"; }
                @Override public AgentContext updateContext(AgentContext ctx, String out) {
                    return ctx.mutate().with(GATE_KEY, "gate-wrote-this").build();
                }
            };

            Workflow<String, String> gatePassWorkflow = Workflow.<String, String>define("gate-pass")
                    .step(passingStep)
                    .build();

            Gate<Object> alwaysPass = (ctx, in) -> GateDecision.PASS;

            AtomicReference<String> captured = new AtomicReference<>();
            Workflow.<String, String>define("gated")
                    .step(Step.named("prepare", (ctx, in) -> in))
                    .gate(alwaysPass)
                        .onPass(gatePassWorkflow)
                        .onFail(Step.named("fail-step", (ctx, in) -> "failed"))
                    .end()
                    .then(Step.named("reader", (ctx, in) -> {
                        captured.set(ctx.get(GATE_KEY).orElse("missing"));
                        return in;
                    }))
                    .run("input");

            assertThat(captured.get()).isEqualTo("gate-wrote-this");
        }
    }

    // =========================================================================
    // Parallel branch context propagation
    // =========================================================================

    @Nested
    class ParallelContextPropagation {

        static final ContextKey<String> BRANCH_A_KEY = ContextKey.of("parallel.branchA", String.class);
        static final ContextKey<String> BRANCH_B_KEY = ContextKey.of("parallel.branchB", String.class);

        @Test
        void parallelBranchContextWritesShouldBeMergedAtJoin() {
            AtomicReference<String> capturedA = new AtomicReference<>();
            AtomicReference<String> capturedB = new AtomicReference<>();

            Step<Object, Object> branchA = new Step<>() {
                @Override public String name() { return "branch-a"; }
                @Override public Object execute(AgentContext ctx, Object in) { return "result-a"; }
                @Override public AgentContext updateContext(AgentContext ctx, Object out) {
                    return ctx.mutate().with(BRANCH_A_KEY, "from-branch-a").build();
                }
            };

            Step<Object, Object> branchB = new Step<>() {
                @Override public String name() { return "branch-b"; }
                @Override public Object execute(AgentContext ctx, Object in) { return "result-b"; }
                @Override public AgentContext updateContext(AgentContext ctx, Object out) {
                    return ctx.mutate().with(BRANCH_B_KEY, "from-branch-b").build();
                }
            };

            Workflow.<String, Object>define("parallel-ctx")
                    .parallel(branchA, branchB)
                    .then(Step.named("reader", (ctx, in) -> {
                        capturedA.set(ctx.get(BRANCH_A_KEY).orElse("missing"));
                        capturedB.set(ctx.get(BRANCH_B_KEY).orElse("missing"));
                        return in;
                    }))
                    .run("input");

            assertThat(capturedA.get()).isEqualTo("from-branch-a");
            assertThat(capturedB.get()).isEqualTo("from-branch-b");
        }

        @Test
        @SuppressWarnings("unchecked")
        void parallelOutputsShouldStillBeCollectedAsListAfterContextFix() {
            Step<Object, Object> branchA = Step.named("branch-a", (ctx, in) -> "a");
            Step<Object, Object> branchB = Step.named("branch-b", (ctx, in) -> "b");

            List<Object> results = (List<Object>) Workflow.<String, Object>define("parallel-outputs")
                    .parallel(branchA, branchB)
                    .run("input");

            assertThat(results).containsExactly("a", "b");
        }
    }

    // =========================================================================
    // Sub-workflow inside branch (the pr-review use case pattern)
    // =========================================================================

    @Nested
    class SubWorkflowInBranch {

        static final ContextKey<String> ASSESSMENT_KEY = ContextKey.of("assessment.result", String.class);

        @Test
        void subWorkflowInBranchOtherwiseShouldPropagateContext() {
            Step<String, String> assessCodeQuality = new Step<>() {
                @Override public String name() { return "assess-quality"; }
                @Override public String execute(AgentContext ctx, String in) { return "quality-result"; }
                @Override public AgentContext updateContext(AgentContext ctx, String out) {
                    return ctx.mutate().with(ASSESSMENT_KEY, "quality-done").build();
                }
            };

            Workflow<String, String> aiAssessment = Workflow.<String, String>define("ai-assessment")
                    .step(assessCodeQuality)
                    .build();

            AtomicReference<String> captured = new AtomicReference<>();
            Workflow.<String, String>define("main")
                    .step(Step.named("check", (ctx, in) -> in))
                    .branch(in -> false) // always take otherwise
                        .then(Step.named("skip-ai", (ctx, in) -> in))
                        .otherwise(aiAssessment)
                    .then(Step.named("reader", (ctx, in) -> {
                        captured.set(ctx.get(ASSESSMENT_KEY).orElse("missing"));
                        return in;
                    }))
                    .run("input");

            assertThat(captured.get()).isEqualTo("quality-done");
        }
    }
}
