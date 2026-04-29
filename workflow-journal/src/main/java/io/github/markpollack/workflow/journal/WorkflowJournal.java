package io.github.markpollack.workflow.journal;

import io.github.markpollack.journal.Run;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;

/**
 * Factory for creating journal-backed workflow recorders.
 *
 * <pre>{@code
 * try (Run run = Journal.run("bom-sync").start()) {
 *     WorkflowExecutor executor = new WorkflowExecutor(
 *         new LocalStepRunner(),
 *         WorkflowJournal.forRun(run)
 *     );
 *     // workflow steps are recorded as CustomEvents in the run
 * }
 * }</pre>
 */
public final class WorkflowJournal {

    private WorkflowJournal() {}

    /**
     * Returns a {@link TraceRecorder} that writes step events into the given run.
     *
     * @param run the active journal run to record into
     * @return a trace recorder backed by the journal run
     */
    public static TraceRecorder forRun(Run run) {
        return new JournalTraceRecorder(run);
    }
}
