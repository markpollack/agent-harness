package io.github.markpollack.workflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentHandler")
class AgentHandlerTest {

    @Test
    void shouldReturnUpperCaseWhenLambdaHandler() {
        AgentHandler<String, String> handler = (ctx, input) -> input.toUpperCase();

        String result = handler.handle(AgentContext.create(), "hello");

        assertThat(result).isEqualTo("HELLO");
    }

    @Test
    void shouldReceiveContextWhenHandlerReadsRunId() {
        AgentHandler<String, String> handler = (ctx, input) -> ctx.runId() + ":" + input;

        AgentContext ctx = AgentContext.withRunId("test-run-42");
        String result = handler.handle(ctx, "payload");

        assertThat(result).isEqualTo("test-run-42:payload");
    }

    @Test
    void shouldBeFunctionalInterface() {
        // AgentHandler is @FunctionalInterface — assignable from lambda
        AgentHandler<Integer, Integer> doubler = (ctx, n) -> n * 2;

        assertThat(doubler.handle(AgentContext.create(), 21)).isEqualTo(42);
    }
}
