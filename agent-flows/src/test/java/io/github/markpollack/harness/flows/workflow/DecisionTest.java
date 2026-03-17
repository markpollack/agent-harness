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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

class DecisionTest {

    // -------------------------------------------------------------------------
    // DecisionStep unit tests
    // -------------------------------------------------------------------------

    @Test
    void decisionStepShouldRouteToChosenOption() {
        ChatClient client = mockClientReturning("option-b");
        Step<String, String> stepA = Step.named("option-a", (ctx, in) -> "chose-A");
        Step<String, String> stepB = Step.named("option-b", (ctx, in) -> "chose-B");

        java.util.Map<String, Step<?, ?>> options = new java.util.LinkedHashMap<>();
        options.put("option-a", stepA);
        options.put("option-b", stepB);

        DecisionStep decision = new DecisionStep("test-decision", client, options,
                DecisionStep.DEFAULT_PROMPT_TEMPLATE);

        Object result = decision.execute(AgentContext.create(), "some input");

        assertThat(result).isEqualTo("chose-B");
    }

    @Test
    void decisionStepShouldRouteToFirstOptionWhenChosen() {
        ChatClient client = mockClientReturning("fix-code");
        Step<String, String> fixCode = Step.named("fix-code", (ctx, in) -> "applied fix");
        Step<String, String> addTest = Step.named("add-test", (ctx, in) -> "added test");

        java.util.Map<String, Step<?, ?>> options = new java.util.LinkedHashMap<>();
        options.put("fix-code", fixCode);
        options.put("add-test", addTest);

        DecisionStep decision = new DecisionStep("router", client, options,
                DecisionStep.DEFAULT_PROMPT_TEMPLATE);

        Object result = decision.execute(AgentContext.create(), "broken code");

        assertThat(result).isEqualTo("applied fix");
    }

    @Test
    void decisionStepShouldThrowOnUnknownOption() {
        ChatClient client = mockClientReturning("nonexistent");
        Step<String, String> stepA = Step.named("option-a", (ctx, in) -> "A");

        java.util.Map<String, Step<?, ?>> options = new java.util.LinkedHashMap<>();
        options.put("option-a", stepA);

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
        Step<String, String> stepA = Step.named("option-a", (ctx, in) -> "trimmed-route");

        java.util.Map<String, Step<?, ?>> options = new java.util.LinkedHashMap<>();
        options.put("option-a", stepA);

        DecisionStep decision = new DecisionStep("test", client, options,
                DecisionStep.DEFAULT_PROMPT_TEMPLATE);

        Object result = decision.execute(AgentContext.create(), "input");

        assertThat(result).isEqualTo("trimmed-route");
    }

    @Test
    void decisionStepNameShouldBePreserved() {
        ChatClient client = mockClientReturning("opt");
        Step<String, String> step = Step.named("opt", (ctx, in) -> in);

        java.util.Map<String, Step<?, ?>> options = new java.util.LinkedHashMap<>();
        options.put("opt", step);

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
    void workflowDecisionNodeShouldBeAgentType() {
        ChatClient client = mockClientReturning("opt");
        Step<String, String> step = Step.named("opt", (ctx, in) -> in);

        WorkflowGraph<String, String> graph = Workflow.<String, String>define("type-test")
                .decision(client)
                    .option("opt", step)
                .end()
                .compile();

        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.nodes().get(0).type())
                .isEqualTo(io.github.markpollack.harness.patterns.graph.NodeType.AGENT);
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
