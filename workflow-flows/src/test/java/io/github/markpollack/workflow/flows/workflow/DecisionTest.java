package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

class DecisionTest {

    // -------------------------------------------------------------------------
    // DecisionStep unit tests (now returns chosen label, not step result)
    // -------------------------------------------------------------------------

    @Test
    void decisionStepShouldReturnChosenLabel() {
        ChatClient client = mockClientReturning("option-b");
        Set<String> options = new LinkedHashSet<>(Set.of("option-a", "option-b"));

        DecisionStep decision = new DecisionStep("test-decision", client, options,
                DecisionStep.DEFAULT_PROMPT_TEMPLATE);

        Object result = decision.execute(AgentContext.create(), "some input");
        assertThat(result).isEqualTo("option-b");
    }

    @Test
    void decisionStepShouldReturnFirstOptionWhenChosen() {
        ChatClient client = mockClientReturning("fix-code");
        Set<String> options = new LinkedHashSet<>(Set.of("fix-code", "add-test"));

        DecisionStep decision = new DecisionStep("router", client, options,
                DecisionStep.DEFAULT_PROMPT_TEMPLATE);

        Object result = decision.execute(AgentContext.create(), "broken code");
        assertThat(result).isEqualTo("fix-code");
    }

    @Test
    void decisionStepShouldThrowOnUnknownOption() {
        ChatClient client = mockClientReturning("nonexistent");
        Set<String> options = new LinkedHashSet<>(Set.of("option-a"));

        DecisionStep decision = new DecisionStep("test", client, options,
                DecisionStep.DEFAULT_PROMPT_TEMPLATE);

        assertThatThrownBy(() -> decision.execute(AgentContext.create(), "input"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonexistent")
                .hasMessageContaining("option-a");
    }

    @Test
    void decisionStepShouldStripWhitespaceFromLlmResponse() {
        ChatClient client = mockClientReturning("  option-a  \n");
        Set<String> options = new LinkedHashSet<>(Set.of("option-a"));

        DecisionStep decision = new DecisionStep("test", client, options,
                DecisionStep.DEFAULT_PROMPT_TEMPLATE);

        Object result = decision.execute(AgentContext.create(), "input");
        assertThat(result).isEqualTo("option-a");
    }

    @Test
    void decisionStepNameShouldBePreserved() {
        ChatClient client = mockClientReturning("opt");
        Set<String> options = new LinkedHashSet<>(Set.of("opt"));

        DecisionStep decision = new DecisionStep("my-router", client, options,
                DecisionStep.DEFAULT_PROMPT_TEMPLATE);

        assertThat(decision.name()).isEqualTo("my-router");
    }

    // -------------------------------------------------------------------------
    // DSL integration tests
    // -------------------------------------------------------------------------

    @Test
    void workflowDecisionShouldRouteViaLlm() {
        ChatClient client = mockClientReturning("summarize");
        Step<String, String> summarize = Step.named("summarize", (ctx, in) -> "summary of: " + in);
        Step<String, String> translate = Step.named("translate", (ctx, in) -> "translation of: " + in);

        String result = Workflow.<String, String>define("route-test")
                .decision(client)
                    .option("summarize", summarize)
                    .option("translate", translate)
                .end()
                .run("some text");

        assertThat(result).isEqualTo("summary of: some text");
    }

    @Test
    void workflowDecisionShouldBeChainableAfterStep() {
        ChatClient client = mockClientReturning("process");
        Step<String, String> preProcess = Step.named("preprocess", (ctx, in) -> in.toUpperCase());
        Step<String, String> process = Step.named("process", (ctx, in) -> "processed: " + in);
        Step<String, String> skip = Step.named("skip", (ctx, in) -> "skipped: " + in);

        String result = Workflow.<String, String>define("chain-test")
                .step(preProcess)
                .decision(client)
                    .option("process", process)
                    .option("skip", skip)
                .end()
                .run("hello");

        assertThat(result).isEqualTo("processed: HELLO");
    }

    @Test
    void workflowDecisionShouldProduceExplodedGraph() {
        ChatClient client = mockClientReturning("opt");
        Step<String, String> step = Step.named("opt", (ctx, in) -> in);

        WorkflowGraph<String, String> graph = Workflow.<String, String>define("type-test")
                .decision(client)
                    .option("opt", step)
                .end()
                .compile();

        // Exploded: DecisionNode + 1 StepNode + JoinNode = 3
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.DecisionNode.class);
        assertThat(graph.nodes().get(0).type())
                .isEqualTo(io.github.markpollack.workflow.patterns.graph.NodeType.AGENT);
    }

    @Test
    void decisionBuilderShouldRejectDuplicateOptionNames() {
        ChatClient client = mockClientReturning("opt");
        Step<String, String> step = Step.named("opt", (ctx, in) -> in);

        assertThatThrownBy(() ->
                Workflow.<String, String>define("dup-test")
                        .decision(client)
                            .option("opt", step)
                            .option("opt", step)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opt");
    }

    @Test
    void decisionBuilderEndWithNoOptionsShouldThrow() {
        ChatClient client = mockClientReturning("anything");

        assertThatThrownBy(() ->
                Workflow.<String, String>define("empty-test")
                        .decision(client)
                        .end()
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("option");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ChatClient mockClientReturning(String response) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(anyString()).call().content()).thenReturn(response);
        return client;
    }
}
