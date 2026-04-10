package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("withExecutor()")
class WithExecutorTest {

    private final Step<String, String> upper = Step.named("upper", (ctx, input) -> input.toUpperCase());

    @Test
    void shouldUseProvidedExecutorInRun() {
        AtomicBoolean customRunnerUsed = new AtomicBoolean(false);
        StepRunner trackingRunner = new StepRunner() {
            @Override
            public <I, O> O execute(Step<I, O> step, AgentContext ctx, I input) {
                customRunnerUsed.set(true);
                return step.execute(ctx, input);
            }
        };
        WorkflowExecutor executor = new WorkflowExecutor(trackingRunner, TraceRecorder.noop());

        String result = Workflow.<String, String>define("test")
                .withExecutor(executor)
                .step(upper)
                .run("hello");

        assertThat(result).isEqualTo("HELLO");
        assertThat(customRunnerUsed).isTrue();
    }

    @Test
    void shouldUseProvidedExecutorInRunWithContext() {
        AtomicBoolean customRunnerUsed = new AtomicBoolean(false);
        StepRunner trackingRunner = new StepRunner() {
            @Override
            public <I, O> O execute(Step<I, O> step, AgentContext ctx, I input) {
                customRunnerUsed.set(true);
                return step.execute(ctx, input);
            }
        };
        WorkflowExecutor executor = new WorkflowExecutor(trackingRunner, TraceRecorder.noop());

        String result = Workflow.<String, String>define("test")
                .withExecutor(executor)
                .step(upper)
                .run("hello", AgentContext.create());

        assertThat(result).isEqualTo("HELLO");
        assertThat(customRunnerUsed).isTrue();
    }

    @Test
    void shouldFallBackToDefaultWithoutWithExecutor() {
        // No withExecutor → default WorkflowExecutor with LocalStepRunner
        String result = Workflow.<String, String>define("test")
                .step(upper)
                .run("hello");

        assertThat(result).isEqualTo("HELLO");
    }

    @Test
    void shouldPassExecutorThroughBuild() {
        AtomicInteger callCount = new AtomicInteger(0);
        StepRunner trackingRunner = new StepRunner() {
            @Override
            public <I, O> O execute(Step<I, O> step, AgentContext ctx, I input) {
                callCount.incrementAndGet();
                return step.execute(ctx, input);
            }
        };
        WorkflowExecutor executor = new WorkflowExecutor(trackingRunner, TraceRecorder.noop());

        Workflow<String, String> workflow = Workflow.<String, String>define("test")
                .withExecutor(executor)
                .step(upper)
                .build();

        String result = workflow.execute(AgentContext.create(), "hello");

        assertThat(result).isEqualTo("HELLO");
        assertThat(callCount).hasValue(1);
    }
}
