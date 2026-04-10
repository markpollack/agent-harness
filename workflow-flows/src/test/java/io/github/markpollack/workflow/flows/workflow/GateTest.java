package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springaicommunity.judge.jury.Jury;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.score.NumericalScore;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GateTest {

    // -------------------------------------------------------------------------
    // Gate routing
    // -------------------------------------------------------------------------

    @Nested
    class Routing {

        @Test
        void gateShouldRouteToPassStepOnPass() {
            Gate<Object> alwaysPass = (ctx, output) -> GateDecision.PASS;

            String result = Workflow.<String, String>define("gate-pass")
                    .step(Step.named("prepare", (ctx, in) -> ((String) in).toUpperCase()))
                    .gate(alwaysPass)
                        .onPass(Step.named("commit", (ctx, in) -> "committed: " + in))
                        .onFail(Step.named("retry", (ctx, in) -> "retried: " + in))
                    .end()
                    .run("hello");

            assertThat(result).isEqualTo("committed: HELLO");
        }

        @Test
        void gateShouldRouteToFailStepOnFail() {
            Gate<Object> alwaysFail = (ctx, output) -> GateDecision.FAIL;

            String result = Workflow.<String, String>define("gate-fail")
                    .step(Step.named("prepare", (ctx, in) -> ((String) in).toUpperCase()))
                    .gate(alwaysFail)
                        .onPass(Step.named("commit", (ctx, in) -> "committed: " + in))
                        .onFail(Step.named("retry", (ctx, in) -> "retried: " + in))
                    .end()
                    .run("hello");

            assertThat(result).isEqualTo("retried: HELLO");
        }

        @Test
        void gateShouldRouteToTimeoutStepOnTimeout() {
            Gate<Object> alwaysTimeout = (ctx, output) -> GateDecision.TIMEOUT;

            String result = Workflow.<String, String>define("gate-timeout")
                    .gate(alwaysTimeout)
                        .onPass(Step.named("deploy", (ctx, in) -> "deployed"))
                        .onFail(Step.named("log", (ctx, in) -> "logged"))
                        .onTimeout(Step.named("escalate", (ctx, in) -> "escalated"))
                    .end()
                    .run("request");

            assertThat(result).isEqualTo("escalated");
        }

        @Test
        void gateShouldPassInputThroughToRouteStep() {
            // The gate evaluates the output but does NOT transform it —
            // the routed step receives the same value that entered the gate
            Gate<Object> scoreGate = (ctx, output) -> {
                double score = (Double) output;
                return score >= 0.8 ? GateDecision.PASS : GateDecision.FAIL;
            };

            String result = Workflow.<Double, String>define("score-gate")
                    .gate(scoreGate)
                        .onPass(Step.named("accept", (ctx, in) -> "accepted: " + in))
                        .onFail(Step.named("reject", (ctx, in) -> "rejected: " + in))
                    .end()
                    .run(0.9);

            assertThat(result).isEqualTo("accepted: 0.9");
        }

        @Test
        void gateShouldWorkInMiddleOfPipeline() {
            Gate<Object> gate = (ctx, output) ->
                    ((String) output).contains("GOOD") ? GateDecision.PASS : GateDecision.FAIL;

            String result = Workflow.<String, String>define("pipeline-gate")
                    .step(Step.named("classify", (ctx, in) -> in + "-GOOD"))
                    .gate(gate)
                        .onPass(Step.named("approve", (ctx, in) -> "approved: " + in))
                        .onFail(Step.named("reject", (ctx, in) -> "rejected: " + in))
                    .end()
                    .then(Step.named("finalize", (ctx, in) -> in + "!"))
                    .run("input");

            assertThat(result).isEqualTo("approved: input-GOOD!");
        }
    }

    // -------------------------------------------------------------------------
    // Topology
    // -------------------------------------------------------------------------

    @Nested
    class Topology {

        @Test
        void gateShouldProduceExplodedGraph() {
            Gate<Object> gate = (ctx, output) -> GateDecision.PASS;

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("gate-topo")
                    .gate(gate)
                        .onPass(Step.named("pass-step", (ctx, in) -> in))
                        .onFail(Step.named("fail-step", (ctx, in) -> in))
                    .end()
                    .compile();

            // GateNode + StepNode(pass) + StepNode(fail) + JoinNode = 4
            assertThat(graph.nodes()).hasSize(4);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.GateNode.class);
            assertThat(graph.nodes().get(1)).isInstanceOf(WorkflowNode.StepNode.class);
            assertThat(graph.nodes().get(2)).isInstanceOf(WorkflowNode.StepNode.class);
            assertThat(graph.nodes().get(3)).isInstanceOf(WorkflowNode.JoinNode.class);
        }

        @Test
        void gateWithTimeoutShouldProduceThreePathGraph() {
            Gate<Object> gate = (ctx, output) -> GateDecision.PASS;

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("gate-3path")
                    .gate(gate)
                        .onPass(Step.named("pass", (ctx, in) -> in))
                        .onFail(Step.named("fail", (ctx, in) -> in))
                        .onTimeout(Step.named("timeout", (ctx, in) -> in))
                    .end()
                    .compile();

            // GateNode + 3 StepNodes + JoinNode = 5
            assertThat(graph.nodes()).hasSize(5);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.GateNode.class);
            assertThat(graph.nodes().get(4)).isInstanceOf(WorkflowNode.JoinNode.class);
        }

        @Test
        void gateNodeShouldCarryJoinNodeName() {
            Gate<Object> gate = (ctx, output) -> GateDecision.PASS;

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("gate-join")
                    .gate(gate)
                        .onPass(Step.named("pass", (ctx, in) -> in))
                    .end()
                    .compile();

            WorkflowNode.GateNode gateNode = (WorkflowNode.GateNode) graph.nodes().get(0);
            WorkflowNode.JoinNode joinNode = (WorkflowNode.JoinNode) graph.nodes().get(graph.nodes().size() - 1);
            assertThat(gateNode.joinNodeName()).isEqualTo(joinNode.name());
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    class EdgeCases {

        @Test
        void gateWithoutOnPassShouldThrow() {
            Gate<Object> gate = (ctx, output) -> GateDecision.PASS;

            assertThatThrownBy(() ->
                    Workflow.<String, String>define("no-pass")
                            .gate(gate)
                            .end())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("onPass");
        }

        @Test
        void gatePassOnlyShouldWork() {
            Gate<Object> gate = (ctx, output) -> GateDecision.PASS;

            String result = Workflow.<String, String>define("pass-only")
                    .gate(gate)
                        .onPass(Step.named("proceed", (ctx, in) -> "ok: " + in))
                    .end()
                    .run("test");

            assertThat(result).isEqualTo("ok: test");
        }
    }

    // -------------------------------------------------------------------------
    // JudgeGate
    // -------------------------------------------------------------------------

    @Nested
    class JudgeGateTests {

        @Test
        void judgeGateShouldPassWhenScoreAboveThreshold() {
            Jury jury = mockJuryReturningScore(0.9);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8);

            String result = Workflow.<String, String>define("judge-pass")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .run("output");

            assertThat(result).isEqualTo("committed");
        }

        @Test
        void judgeGateShouldFailWhenScoreBelowThreshold() {
            Jury jury = mockJuryReturningScore(0.5);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8);

            String result = Workflow.<String, String>define("judge-fail")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .run("output");

            assertThat(result).isEqualTo("retried");
        }

        @Test
        void judgeGateFailShouldWriteVerdictToContext() {
            Jury jury = mockJuryReturningScore(0.3);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8);
            AtomicReference<Object> capturedVerdict = new AtomicReference<>();

            Workflow.<String, String>define("verdict-feedback")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> {
                            capturedVerdict.set(ctx.get(AgentContext.JUDGE_VERDICT).orElse(null));
                            return "retried";
                        }))
                    .end()
                    .run("output");

            assertThat(capturedVerdict.get()).isNotNull();
            assertThat(capturedVerdict.get()).isInstanceOf(Verdict.class);
        }
    }

    // -------------------------------------------------------------------------
    // TieredGate
    // -------------------------------------------------------------------------

    @Nested
    class TieredGateTests {

        @Test
        void tieredGateShouldPassOnHighScore() {
            Jury jury = mockJuryReturningScore(0.95);
            TieredGate<Object> gate = new TieredGate<>(jury, 0.9, 0.6);

            GateDecision decision = gate.evaluate(AgentContext.create(), "output");
            assertThat(decision).isEqualTo(GateDecision.PASS);
        }

        @Test
        void tieredGateShouldEscalateOnBorderlineScore() {
            Jury jury = mockJuryReturningScore(0.75);
            TieredGate<Object> gate = new TieredGate<>(jury, 0.9, 0.6);

            GateDecision decision = gate.evaluate(AgentContext.create(), "output");
            assertThat(decision).isEqualTo(GateDecision.ESCALATE);
        }

        @Test
        void tieredGateShouldFailOnLowScore() {
            Jury jury = mockJuryReturningScore(0.3);
            TieredGate<Object> gate = new TieredGate<>(jury, 0.9, 0.6);

            GateDecision decision = gate.evaluate(AgentContext.create(), "output");
            assertThat(decision).isEqualTo(GateDecision.FAIL);
        }
    }

    // -------------------------------------------------------------------------
    // Reflector
    // -------------------------------------------------------------------------

    @Nested
    class ReflectorTests {

        @Test
        void reflectorShouldWriteFeedbackToContextOnFail() {
            Jury jury = mockJuryReturningScore(0.3);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8);
            AtomicReference<String> capturedReflection = new AtomicReference<>();

            Step<?, ?> reflector = Step.named("reflector",
                    (ctx, verdict) -> "Improve: score was too low");

            Workflow.<String, String>define("reflector-test")
                    .gate(gate)
                        .withReflector(reflector)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> {
                            capturedReflection.set(
                                    ctx.get(AgentContext.JUDGE_REFLECTION).orElse(null));
                            return "retried";
                        }))
                    .end()
                    .run("output");

            assertThat(capturedReflection.get()).isEqualTo("Improve: score was too low");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Jury mockJuryReturningScore(double score) {
        Jury jury = mock(Jury.class);
        Verdict verdict = Verdict.builder()
                .aggregated(Judgment.builder()
                        .score(new NumericalScore(score, 0.0, 1.0))
                        .status(score >= 0.5
                                ? org.springaicommunity.judge.result.JudgmentStatus.PASS
                                : org.springaicommunity.judge.result.JudgmentStatus.FAIL)
                        .reasoning("Score: " + score)
                        .build())
                .individual(List.of())
                .build();
        when(jury.vote(any())).thenReturn(verdict);
        return jury;
    }
}
