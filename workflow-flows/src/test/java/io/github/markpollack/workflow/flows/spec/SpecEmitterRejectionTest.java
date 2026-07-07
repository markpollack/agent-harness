package io.github.markpollack.workflow.flows.spec;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Gate;
import io.github.markpollack.workflow.flows.workflow.GateDecision;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Non-alpha primitives MUST fail with a clear "not expressible in v2-alpha" error —
 * never silently mis-emit (DD-20 rationale; RISKS R2).
 */
class SpecEmitterRejectionTest {

    private static final Step<Object, Object> A = Step.named("a", (ctx, in) -> in);
    private static final Step<Object, Object> B = Step.named("b", (ctx, in) -> in);
    private static final Step<Object, Object> C = Step.named("c", (ctx, in) -> in);

    @Test
    void parallelIsRejected() {
        assertNotExpressible(Workflow.define("p").step(A).parallel(B, C), "parallel");
    }

    @Test
    void gatherIsRejected() {
        assertNotExpressible(Workflow.define("g").step(A).gather(B, C), "parallel");
    }

    @Test
    void dynamicParallelIsRejected() {
        assertNotExpressible(Workflow.define("dp")
                .step(A)
                .parallel(ctx -> List.of(1, 2), item -> B), "dynamic parallel");
    }

    @Test
    void repeatUntilIsRejected() {
        assertNotExpressible(Workflow.define("ru")
                .repeatUntil(ctx -> true).step(A).end(), "repeatUntil");
    }

    @Test
    void repeatUntilOutputIsRejected() {
        assertNotExpressible(Workflow.define("ruo")
                .repeatUntilOutput(o -> true).step(A).end(), "repeatUntilOutput");
    }

    @Test
    void gateIsRejected() {
        Gate<Object> gate = (ctx, output) -> GateDecision.PASS;
        assertNotExpressible(Workflow.define("gt")
                .step(A).gate(gate).onPass(B).end(), "gate");
    }

    @Test
    void supervisorIsRejected() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        Workflow<Object, Object> supervisor = Workflow.supervisor("sup", client)
                .agents(A, B)
                .until(ctx -> ctx.get(AgentContext.ITERATION_COUNT).orElse(0) >= 1)
                .build();
        assertThatThrownBy(supervisor::toSpec)
                .isInstanceOf(SpecEmissionException.class)
                .hasMessageContaining("not expressible in v2-alpha");
    }

    @Test
    void onErrorIsDeferredWithAClearError() {
        assertThatThrownBy(() -> Workflow.define("oe")
                .step(A).onError(IllegalStateException.class, B).then(C)
                .toSpec())
                .isInstanceOf(SpecEmissionException.class)
                .hasMessageContaining("onError")
                .hasMessageContaining("error codes");
    }

    @Test
    void backToIsRejected() {
        assertNotExpressible(Workflow.define("bt")
                .step(A).step(B).backTo("a", o -> false), "backTo");
    }

    private static void assertNotExpressible(Workflow.WorkflowBuilder<?, ?> builder, String fragment) {
        assertThatThrownBy(builder::toSpec)
                .isInstanceOf(SpecEmissionException.class)
                .hasMessageContaining("not expressible in v2-alpha")
                .hasMessageContaining(fragment);
    }
}
