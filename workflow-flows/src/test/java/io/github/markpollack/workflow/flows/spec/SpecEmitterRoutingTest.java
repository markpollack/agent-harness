package io.github.markpollack.workflow.flows.spec;

import io.github.markpollack.workflow.engine.InMemoryEventSink;
import io.github.markpollack.workflow.engine.SimpleOperationRegistry;
import io.github.markpollack.workflow.engine.WorkflowInterpreter;
import io.github.markpollack.workflow.engine.WorkflowRunOutcome;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.spec.DecisionSpecNode;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.TerminateSpecNode;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Branch (predicate) and decision (LLM routing) emission and v2 execution. */
class SpecEmitterRoutingTest {

    private static Workflow<String, Object> branchWorkflow() {
        return Workflow.<String, Object>define("triage")
                .step(Step.named("classify", (ctx, in) -> in))
                .branch(o -> ((String) o).startsWith("med"))
                    .then(Step.named("medical", (ctx, in) -> "MED:" + in))
                    .otherwise(Step.named("legal", (ctx, in) -> "LEGAL:" + in))
                .build();
    }

    @Test
    void branchEmitsDecisionNodeWithConvergenceContextWrites() {
        WorkflowSpec spec = branchWorkflow().toSpec().spec();

        assertThat(spec.nodes()).extracting("id")
                .containsExactly("classify", "branch-0", "medical", "legal", "done");

        DecisionSpecNode branch = (DecisionSpecNode) spec.nodes().get(1);
        assertThat(branch.outcomes()).containsExactly("true", "false");
        assertThat(branch.input().get("value").from()).isEqualTo("$node.classify.output");

        // arms receive the pre-decision value (v1 semantics) and converge via context
        TaskSpecNode medical = (TaskSpecNode) spec.nodes().get(2);
        assertThat(medical.input().get("value").from()).isEqualTo("$node.classify.output");
        assertThat(medical.contextWrites().get("branch-0.result").from())
                .isEqualTo("$node.medical.output");

        TerminateSpecNode done = (TerminateSpecNode) spec.nodes().get(4);
        assertThat(done.result().from()).isEqualTo("$context.branch-0.result");
        assertThat(spec.outputs().get("result").from()).isEqualTo("$context.branch-0.result");

        assertThat(spec.edges()).extracting("from", "to")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("classify", "branch-0"),
                        org.assertj.core.groups.Tuple.tuple("branch-0", "medical"),
                        org.assertj.core.groups.Tuple.tuple("branch-0", "legal"),
                        org.assertj.core.groups.Tuple.tuple("medical", "done"),
                        org.assertj.core.groups.Tuple.tuple("legal", "done"));
    }

    @Test
    void branchExecutesBothArmsOnV2Interpreter() {
        assertThat(runBranch("medical question").result()).isEqualTo("MED:medical question");
        assertThat(runBranch("legal question").result()).isEqualTo("LEGAL:legal question");
    }

    private static WorkflowRunOutcome runBranch(String input) {
        WorkflowSpecEmission emission = branchWorkflow().toSpec();
        WorkflowInterpreter interpreter = new WorkflowInterpreter(
                emission.registerInto(new SimpleOperationRegistry()), new InMemoryEventSink());
        WorkflowRunOutcome outcome = interpreter.run(emission.spec(), "run-" + input.hashCode(), input);
        assertThat(outcome.completed()).isTrue();
        return outcome;
    }

    @Test
    void decisionEmitsAndRoutesTheChosenOption() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(anyString()).call().content()).thenReturn("summarize");
        AtomicReference<Object> optionInput = new AtomicReference<>();

        Workflow<String, Object> router = Workflow.<String, Object>define("router")
                .decision(client)
                    .option("summarize", Step.named("summarize", (ctx, in) -> {
                        optionInput.set(in);
                        return "SUM:" + in;
                    }))
                    .option("translate", Step.named("translate", (ctx, in) -> "TR:" + in))
                .end()
                .build();

        WorkflowSpecEmission emission = router.toSpec();
        WorkflowSpec spec = emission.spec();
        assertThat(spec.entrypoint()).isEqualTo("decision-0");
        DecisionSpecNode decision = (DecisionSpecNode) spec.nodes().get(0);
        assertThat(decision.input().get("value").from()).isEqualTo("$input");
        assertThat(decision.outcomes()).isEqualTo(List.of("summarize", "translate"));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(
                emission.registerInto(new SimpleOperationRegistry()), new InMemoryEventSink())
                .run(spec, "run-router", "hello");

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("SUM:hello");
        // v1 parity: the chosen option received the pre-decision value, not the label
        assertThat(optionInput.get()).isEqualTo("hello");
    }
}
