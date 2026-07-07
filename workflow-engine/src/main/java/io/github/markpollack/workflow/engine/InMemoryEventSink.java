package io.github.markpollack.workflow.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The canonical sink for non-durable interpreters and tests (§10): records events in
 * emission order, in memory. Non-durable emission still guarantees monotonic sequences
 * and preserved control-flow decision order — those come from the emitter; this sink
 * just never reorders or drops.
 */
public final class InMemoryEventSink implements WorkflowEventSink {

    private final List<WorkflowEvent> events = new ArrayList<>();

    @Override
    public synchronized void emit(WorkflowEvent event) {
        Objects.requireNonNull(event, "event");
        events.add(event);
    }

    /** An immutable snapshot of everything emitted so far, in emission order. */
    public synchronized List<WorkflowEvent> events() {
        return List.copyOf(events);
    }
}
