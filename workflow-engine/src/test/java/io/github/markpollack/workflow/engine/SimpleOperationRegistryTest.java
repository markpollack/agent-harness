package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.core.AgentContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Registry contract: fail-fast resolution, no silent re-registration (R1 hazard). */
class SimpleOperationRegistryTest {

    private static final OperationHandler NOOP =
            (invocation, context, input) -> OperationResult.success(input);

    @Test
    void resolvesRegisteredHandler() {
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:fetch-diff", NOOP);

        assertThat(registry.resolve("java:fetch-diff")).isSameAs(NOOP);
    }

    @Test
    void unknownRefFailsFastWithRefInException() {
        SimpleOperationRegistry registry = new SimpleOperationRegistry();

        assertThatThrownBy(() -> registry.resolve("java:missing"))
                .isInstanceOf(UnknownOperationException.class)
                .hasMessageContaining("java:missing")
                .extracting(ex -> ((UnknownOperationException) ex).operationRef())
                .isEqualTo("java:missing");
    }

    @Test
    void duplicateRegistrationIsRejectedNotReplaced() {
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:fetch-diff", NOOP);

        OperationHandler other = (invocation, context, input) -> OperationResult.success("other");

        assertThatIllegalStateException()
                .isThrownBy(() -> registry.register("java:fetch-diff", other))
                .withMessageContaining("java:fetch-diff");
        assertThat(registry.resolve("java:fetch-diff")).isSameAs(NOOP);
    }

    @Test
    void nullArgumentsAreRejected() {
        SimpleOperationRegistry registry = new SimpleOperationRegistry();

        assertThatNullPointerException().isThrownBy(() -> registry.register(null, NOOP));
        assertThatNullPointerException().isThrownBy(() -> registry.register("ref", null));
        assertThatNullPointerException().isThrownBy(() -> registry.resolve(null));
    }

    @Test
    void invocationIdentityReachesTheHandler() {
        AtomicReference<OperationInvocation> seen = new AtomicReference<>();
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:record-identity", (invocation, context, input) -> {
                    seen.set(invocation);
                    return OperationResult.success(input);
                });
        OperationInvocation invocation =
                new OperationInvocation("run-42", "fetch", "java:record-identity", 3);

        registry.resolve("java:record-identity")
                .execute(invocation, AgentContext.withRunId("run-42"), "payload");

        assertThat(seen.get()).isEqualTo(invocation);
        assertThat(seen.get().attemptNumber()).isEqualTo(3);
    }
}
