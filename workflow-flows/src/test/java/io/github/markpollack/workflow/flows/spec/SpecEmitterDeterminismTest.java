package io.github.markpollack.workflow.flows.spec;

import io.github.markpollack.workflow.engine.SimpleOperationRegistry;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecWriter;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DD-21 naming determinism (RISKS R1 is live here): refs are a pure function of
 * workflow structure — stable across repeated {@code .toSpec()} calls and across
 * rebuilds from identical code (the simulated JVM restart); collisions surface
 * loudly, never last-wins.
 */
class SpecEmitterDeterminismTest {

    /** Builds the same workflow from identical code — the "same code after restart" stand-in. */
    private static Workflow<String, Object> build() {
        return Workflow.<String, Object>define("stable")
                .step(Step.named("first", (ctx, in) -> in))
                .branch(o -> true)
                    .then(Step.named("yes", (ctx, in) -> in))
                    .otherwise(Step.named("no", (ctx, in) -> in))
                .build();
    }

    private static byte[] canonicalBytes(WorkflowSpec spec) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DefaultWorkflowSpecWriter().write(spec, out);
        return out.toByteArray();
    }

    @Test
    void repeatedToSpecCallsEmitIdenticalBytesAndRefs() {
        Workflow<String, Object> workflow = build();
        WorkflowSpecEmission first = workflow.toSpec();
        WorkflowSpecEmission second = workflow.toSpec();

        assertThat(canonicalBytes(first.spec())).isEqualTo(canonicalBytes(second.spec()));
        assertThat(first.handlersByRef().keySet())
                .containsExactlyElementsOf(second.handlersByRef().keySet());
    }

    @Test
    void rebuildFromIdenticalCodeEmitsIdenticalBytes() {
        assertThat(canonicalBytes(build().toSpec().spec()))
                .isEqualTo(canonicalBytes(build().toSpec().spec()));
    }

    @Test
    void syntheticLambdaNamesFallBackToPositionalIds() {
        Workflow<String, Object> workflow = Workflow.<String, Object>define("anon")
                .step((ctx, in) -> in)
                .then(Step.named("named", (ctx, in) -> in))
                .build();

        WorkflowSpecEmission emission = workflow.toSpec();
        assertThat(emission.spec().nodes()).extracting("id")
                .containsExactly("step-0", "named", "done");
        assertThat(emission.handlersByRef()).containsKey("java:anon.step-0:v1");
    }

    @Test
    void distinctBehaviorsSharingANameCollideLoudly() {
        Workflow.WorkflowBuilder<String, Object> builder = Workflow.<String, Object>define("clash")
                .step(Step.named("same", (ctx, in) -> "one"))
                .then(Step.named("same", (ctx, in) -> "two"));

        assertThatThrownBy(builder::toSpec)
                .isInstanceOf(SpecEmissionException.class)
                .hasMessageContaining("R1")
                .hasMessageContaining("same");
    }

    @Test
    void reusedStepWithMixedInputFramingsCollidesLoudly() {
        // one behavior → one handler → one input framing: explicit bindings on one
        // occurrence and auto-threading on another would silently mis-frame one node
        Step<Object, Object> shared = Step.named("shared", (ctx, in) -> in);
        Workflow<String, Object> workflow = Workflow.<String, Object>define("mixed")
                .step(shared)
                .then(Step.named("mid", (ctx, in) -> in))
                .then(shared)
                .build();
        SpecEmitterOptions options = SpecEmitterOptions.builder()
                .node("shared", n -> n.input("payload", "$input"))
                .build();

        assertThatThrownBy(() -> workflow.toSpec(options))
                .isInstanceOf(SpecEmissionException.class)
                .hasMessageContaining("framing");
    }

    @Test
    void multiLeafConvergenceAcrossIndependentPathsIsRejected() {
        // unreachable through the v1 builder (arms are single steps), but
        // SpecEmitter.emit is public over WorkflowGraph — guard the silent path
        io.github.markpollack.workflow.flows.workflow.WorkflowGraph<Object, Object> graph =
                io.github.markpollack.workflow.flows.workflow.WorkflowGraph.of("manual",
                        java.util.List.of(
                                io.github.markpollack.workflow.flows.workflow.WorkflowNode
                                        .deterministic("a", Step.named("a", (ctx, in) -> in)),
                                io.github.markpollack.workflow.flows.workflow.WorkflowNode
                                        .deterministic("x", Step.named("x", (ctx, in) -> in)),
                                io.github.markpollack.workflow.flows.workflow.WorkflowNode
                                        .deterministic("y", Step.named("y", (ctx, in) -> in))),
                        java.util.List.of(
                                io.github.markpollack.workflow.flows.workflow.WorkflowEdge
                                        .sequence("a", "x"),
                                io.github.markpollack.workflow.flows.workflow.WorkflowEdge
                                        .sequence("a", "y")),
                        "a", "y");

        assertThatThrownBy(() -> SpecEmitter.emit(graph, SpecEmitterOptions.defaults()))
                .isInstanceOf(SpecEmissionException.class)
                .hasMessageContaining("workflow end");
    }

    @Test
    void registryRejectsCollidingRefsAcrossEmissions() {
        // two different workflows that structurally derive the same ref: the registry —
        // not last-wins — is the backstop (R1 surfacing exactly where it should)
        SimpleOperationRegistry registry = new SimpleOperationRegistry();
        Workflow.<String, Object>define("dup")
                .step(Step.named("work", (ctx, in) -> "a")).build()
                .toSpec().registerInto(registry);

        WorkflowSpecEmission conflicting = Workflow.<String, Object>define("dup")
                .step(Step.named("work", (ctx, in) -> "b")).build()
                .toSpec();

        assertThatThrownBy(() -> conflicting.registerInto(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }
}
