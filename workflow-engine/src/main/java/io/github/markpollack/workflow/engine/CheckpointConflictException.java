package io.github.markpollack.workflow.engine;

/**
 * A commit lost the optimistic race or broke the committed-event rule (§10): the
 * store's version guard (or event-sequence contiguity check) rejected the write.
 * Under the documented single-runner-per-run constraint (D1, unenforced) this
 * indicates two interpreters driving one run.
 */
public class CheckpointConflictException extends RuntimeException {

    public CheckpointConflictException(String message) {
        super(message);
    }

    public CheckpointConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
