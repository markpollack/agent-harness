package io.github.markpollack.workflow.engine;

/**
 * A durable run's interpreter thread was interrupted (graceful shutdown) while
 * waiting — the run's committed state is intact and it remains RESUMABLE. Thrown
 * instead of committing a terminal abort: a polite shutdown must never be more
 * destructive than {@code kill -9} (which leaves the run resumable by definition).
 * The ephemeral interpreter keeps the §17 behavior (the run aborts — there is
 * nothing to resume). The thread's interrupt flag is restored before this is thrown.
 */
public class WorkflowInterruptedException extends RuntimeException {

    public WorkflowInterruptedException(String message) {
        super(message);
    }
}
