package io.github.markpollack.workflow.flows.spec;

import io.github.markpollack.workflow.engine.InMemoryEventSink;
import io.github.markpollack.workflow.engine.SimpleOperationRegistry;
import io.github.markpollack.workflow.engine.WorkflowInterpreter;
import io.github.markpollack.workflow.engine.WorkflowRunOutcome;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.spec.CanonicalJson;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecReader;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecWriter;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.TerminateSpecNode;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Sequential (step/then) emission: shape, both validation phases, v2 execution. */
class SpecEmitterSequentialTest {

    private static Workflow<String, Object> greetWorkflow() {
        return Workflow.<String, Object>define("greet")
                .step(Step.named("upper", (ctx, in) -> ((String) in).toUpperCase()))
                .then(Step.named("exclaim", (ctx, in) -> in + "!"))
                .build();
    }

    @Test
    void emitsSequentialChainWithAutoDerivedStructure() {
        WorkflowSpec spec = greetWorkflow().toSpec().spec();

        assertThat(spec.metadata().name()).isEqualTo("greet");
        assertThat(spec.entrypoint()).isEqualTo("upper");
        assertThat(spec.nodes()).extracting("id")
                .containsExactly("upper", "exclaim", "done");

        TaskSpecNode upper = (TaskSpecNode) spec.nodes().get(0);
        assertThat(upper.operation()).isEqualTo("upper");
        assertThat(upper.input()).containsOnlyKeys("value");
        assertThat(upper.input().get("value").from()).isEqualTo("$input");

        TaskSpecNode exclaim = (TaskSpecNode) spec.nodes().get(1);
        assertThat(exclaim.input().get("value").from()).isEqualTo("$node.upper.output");

        TerminateSpecNode done = (TerminateSpecNode) spec.nodes().get(2);
        assertThat(done.result().from()).isEqualTo("$node.exclaim.output");

        assertThat(spec.operations().get("upper").ref()).isEqualTo("java:greet.upper:v1");
        assertThat(spec.operations().get("exclaim").ref()).isEqualTo("java:greet.exclaim:v1");
        assertThat(spec.outputs()).containsOnlyKeys("result");
        assertThat(spec.outputs().get("result").from()).isEqualTo("$node.exclaim.output");
        assertThat(spec.edges()).extracting("from", "to")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("upper", "exclaim"),
                        org.assertj.core.groups.Tuple.tuple("exclaim", "done"));
    }

    @Test
    void emittedSpecPassesBothValidationPhases() {
        WorkflowSpec spec = greetWorkflow().toSpec().spec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DefaultWorkflowSpecWriter().write(spec, out);

        WorkflowSpec reread = new DefaultWorkflowSpecReader().read(new ByteArrayInputStream(out.toByteArray()));

        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        new DefaultWorkflowSpecWriter().write(reread, rewritten);
        assertThat(rewritten.toByteArray()).isEqualTo(out.toByteArray());
    }

    @Test
    void executesOnV2InterpreterWithV1InputSemantics() {
        WorkflowSpecEmission emission = greetWorkflow().toSpec();
        SimpleOperationRegistry registry = emission.registerInto(new SimpleOperationRegistry());
        WorkflowInterpreter interpreter = new WorkflowInterpreter(registry, new InMemoryEventSink());

        WorkflowRunOutcome outcome = interpreter.run(emission.spec(), "run-1", "hi");

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).isEqualTo("HI!");
        assertThat(outcome.outputs()).containsEntry("result", "HI!");
    }

    @Test
    void subWorkflowStepEmitsAsOpaqueTask() {
        Workflow<Object, Object> sub = Workflow.define("shout")
                .step(Step.named("upper", (ctx, in) -> ((String) in).toUpperCase()))
                .build();
        Workflow<String, Object> outer = Workflow.<String, Object>define("outer")
                .step(sub)
                .then(Step.named("wrap", (ctx, in) -> "[" + in + "]"))
                .build();

        WorkflowSpecEmission emission = outer.toSpec();
        assertThat(emission.spec().nodes()).extracting("id").containsExactly("shout", "wrap", "done");
        assertThat(emission.spec().operations().get("shout").ref()).isEqualTo("java:outer.shout:v1");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(
                emission.registerInto(new SimpleOperationRegistry()), new InMemoryEventSink())
                .run(emission.spec(), "run-sub", "hi");
        assertThat(outcome.result()).isEqualTo("[HI]");
    }

    @Test
    void reusedStepInstanceDeclaresOneOperationWithOccurrenceSuffixedNodeIds() {
        Step<Object, Object> polish = Step.named("polish", (ctx, in) -> in + "*");
        WorkflowSpecEmission emission = Workflow.define("shine")
                .step(polish)
                .then(Step.named("middle", (ctx, in) -> in))
                .then(polish)
                .toSpec();

        assertThat(emission.spec().nodes()).extracting("id")
                .containsExactly("polish", "middle", "polish-2", "done");
        assertThat(emission.spec().operations()).containsOnlyKeys("polish", "middle");
        assertThat(emission.handlersByRef()).containsOnlyKeys(
                "java:shine.polish:v1", "java:shine.middle:v1");

        WorkflowRunOutcome outcome = new WorkflowInterpreter(
                emission.registerInto(new SimpleOperationRegistry()), new InMemoryEventSink())
                .run(emission.spec(), "run-reuse", "x");
        assertThat(outcome.result()).isEqualTo("x**");
    }

    @Test
    void canonicalBytesAreCanonicalForm() {
        WorkflowSpec spec = greetWorkflow().toSpec().spec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DefaultWorkflowSpecWriter().write(spec, out);
        assertThat(out.toByteArray()).isEqualTo(CanonicalJson.canonicalize(out.toByteArray()));
    }
}
