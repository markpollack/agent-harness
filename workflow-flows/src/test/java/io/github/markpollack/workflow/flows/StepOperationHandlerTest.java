package io.github.markpollack.workflow.flows;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.engine.OperationInvocation;
import io.github.markpollack.workflow.engine.OperationResult;
import io.github.markpollack.workflow.engine.SimpleOperationRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The Step library runs as v2 operations through the adapter: direct
 * {@code step.execute()}, exceptions normalized at the handler boundary, no v1
 * runtime machinery.
 */
class StepOperationHandlerTest {

    private static final OperationInvocation INVOCATION =
            new OperationInvocation("run-1", "greet", "java:greet", 1);
    private static final AgentContext CONTEXT = AgentContext.withRunId("run-1");

    @Test
    void existingStepExecutesThroughRegistryAndAdapter() {
        Step<String, String> step = Step.named("greet", (ctx, name) -> "hello " + name);
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:greet", new StepOperationHandler(step));

        OperationResult result = registry.resolve("java:greet")
                .execute(INVOCATION, CONTEXT, "mark");

        assertThat(result.successful()).isTrue();
        assertThat(((OperationResult.Success) result).output()).isEqualTo("hello mark");
    }

    @Test
    void stepReadsTheAgentContextItWasHanded() {
        Step<Object, String> step = Step.named("who", (ctx, in) -> ctx.runId());

        OperationResult result = new StepOperationHandler(step)
                .execute(INVOCATION, CONTEXT, null);

        assertThat(((OperationResult.Success) result).output()).isEqualTo("run-1");
    }

    @Test
    void nullStepOutputIsAValidSuccess() {
        Step<Object, Object> step = Step.named("void-like", (ctx, in) -> null);

        OperationResult result = new StepOperationHandler(step)
                .execute(INVOCATION, CONTEXT, "in");

        assertThat(result.successful()).isTrue();
        assertThat(((OperationResult.Success) result).output()).isNull();
    }

    @Test
    void thrownExceptionNormalizesToRetryableFailureWithStableCode() {
        Step<Object, Object> step = Step.named("boom", (ctx, in) -> {
            throw new IllegalStateException("upstream unavailable");
        });

        OperationResult result = new StepOperationHandler(step)
                .execute(INVOCATION, CONTEXT, "in");

        assertThat(result).isInstanceOf(OperationResult.Failure.class);
        OperationResult.Failure failure = (OperationResult.Failure) result;
        assertThat(failure.error().code()).isEqualTo(StepOperationHandler.STEP_EXECUTION_FAILED);
        assertThat(failure.error().message()).isEqualTo("upstream unavailable");
        assertThat(failure.error().retryable()).isTrue();
        assertThat(failure.error().details())
                .containsEntry("exceptionClass", "java.lang.IllegalStateException");
        assertThat(result.retryable()).isTrue();
        assertThat(result.routable()).isTrue();
    }

    @Test
    void messagelessExceptionFallsBackToClassSimpleName() {
        Step<Object, Object> step = Step.named("boom", (ctx, in) -> {
            throw new IllegalStateException();
        });

        OperationResult result = new StepOperationHandler(step)
                .execute(INVOCATION, CONTEXT, "in");

        assertThat(((OperationResult.Failure) result).error().message())
                .isEqualTo("IllegalStateException");
    }

    @Test
    void updateContextIsNeverInvoked() {
        AtomicBoolean updateContextCalled = new AtomicBoolean(false);
        Step<Object, Object> step = new Step<>() {
            @Override
            public Object execute(AgentContext ctx, Object input) {
                return input;
            }

            @Override
            public AgentContext updateContext(AgentContext ctx, Object output) {
                updateContextCalled.set(true);
                return ctx;
            }
        };

        new StepOperationHandler(step).execute(INVOCATION, CONTEXT, "in");

        assertThat(updateContextCalled)
                .as("context mutation is IR-declared in v2 (§14); the adapter must not call updateContext")
                .isFalse();
    }

    @Test
    void nullStepIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new StepOperationHandler(null));
    }
}
