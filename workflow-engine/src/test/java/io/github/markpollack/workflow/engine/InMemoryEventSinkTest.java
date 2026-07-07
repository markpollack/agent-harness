package io.github.markpollack.workflow.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** The canonical in-memory sink: emission order preserved, snapshots stable. */
class InMemoryEventSinkTest {

    private final WorkflowEventFactory factory =
            new WorkflowEventFactory("run-1", "workflow://registry/w@1.0.0");
    private final InMemoryEventSink sink = new InMemoryEventSink();

    @Test
    void preservesEmissionOrderAndMonotonicSequence() {
        sink.emit(factory.workflowStarted("w", "a"));
        sink.emit(factory.nodeStarted("a", "task"));
        sink.emit(factory.operationDispatched("a", "java:op", 1));

        List<WorkflowEvent> events = sink.events();
        assertThat(events).extracting(WorkflowEvent::eventType).containsExactly(
                WorkflowEventType.WORKFLOW_STARTED,
                WorkflowEventType.NODE_STARTED,
                WorkflowEventType.OPERATION_DISPATCHED);
        assertThat(events).extracting(WorkflowEvent::sequence).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void snapshotIsImmutableAndStable() {
        sink.emit(factory.workflowStarted("w", "a"));
        List<WorkflowEvent> snapshot = sink.events();

        sink.emit(factory.nodeStarted("a", "task"));

        assertThat(snapshot).hasSize(1);
        assertThat(sink.events()).hasSize(2);
        assertThatNullPointerException().isThrownBy(() -> sink.emit(null));
    }
}
