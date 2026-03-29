package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

class SupervisorTest {

    @Test
    void supervisorShouldInvokeChosenAgentOnEachIteration() {
        // LLM always picks "review" agent
        ChatClient client = mockClientReturning("review");
        AtomicInteger reviewCalls = new AtomicInteger();
        AtomicInteger auditCalls = new AtomicInteger();

        Step<Object, Object> review = Step.named("review", (ctx, in) -> {
            reviewCalls.incrementAndGet();
            return "reviewed: " + in;
        });
        Step<Object, Object> audit = Step.named("audit", (ctx, in) -> {
            auditCalls.incrementAndGet();
            return "audited: " + in;
        });

        Object result = Workflow.<String, Object>supervisor("test-supervisor", client)
                .agents(review, audit)
                .until(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 3)
                .run("input");

        assertThat(reviewCalls.get()).isEqualTo(3);
        assertThat(auditCalls.get()).isEqualTo(0);
    }

    @Test
    void supervisorShouldInvokeDifferentAgentsWhenLlmSwitches() {
        // LLM alternates between agents
        List<String> responses = new ArrayList<>(List.of("review", "audit", "review"));
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(anyString()).call().content())
                .thenAnswer(inv -> responses.remove(0));

        AtomicInteger reviewCalls = new AtomicInteger();
        AtomicInteger auditCalls = new AtomicInteger();

        Step<Object, Object> review = Step.named("review", (ctx, in) -> {
            reviewCalls.incrementAndGet();
            return "reviewed";
        });
        Step<Object, Object> audit = Step.named("audit", (ctx, in) -> {
            auditCalls.incrementAndGet();
            return "audited";
        });

        Workflow.<String, Object>supervisor("alternating", client)
                .agents(review, audit)
                .until(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 3)
                .run("input");

        assertThat(reviewCalls.get()).isEqualTo(2);
        assertThat(auditCalls.get()).isEqualTo(1);
    }

    @Test
    void supervisorShouldRespectMaxIterations() {
        ChatClient client = mockClientReturning("work");
        AtomicInteger calls = new AtomicInteger();

        Step<Object, Object> work = Step.named("work", (ctx, in) -> {
            calls.incrementAndGet();
            return "done";
        });

        Workflow.<String, Object>supervisor("limited", client)
                .agents(work)
                .maxIterations(5)
                .run("input");

        assertThat(calls.get()).isEqualTo(5);
    }

    @Test
    void supervisorShouldBuildAsWorkflow() {
        ChatClient client = mockClientReturning("agent");
        Step<Object, Object> agent = Step.named("agent", (ctx, in) -> "result");

        Workflow<String, Object> supervisor = Workflow.<String, Object>supervisor("composable", client)
                .agents(agent)
                .until(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 1)
                .build();

        assertThat(supervisor.name()).isEqualTo("composable");
        Object result = supervisor.execute(AgentContext.create(), "input");
        assertThat(result).isEqualTo("result");
    }

    @Test
    void supervisorWithNoAgentsShouldThrow() {
        ChatClient client = mockClientReturning("anything");

        assertThatThrownBy(() ->
                Workflow.<String, Object>supervisor("empty", client)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent");
    }

    private static ChatClient mockClientReturning(String response) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(anyString()).call().content()).thenReturn(response);
        return client;
    }
}
