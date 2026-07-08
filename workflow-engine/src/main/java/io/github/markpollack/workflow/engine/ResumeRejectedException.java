package io.github.markpollack.workflow.engine;

/**
 * Resume refused: the run is already terminal, or the supplied spec's canonical hash
 * differs from the one pinned at {@code openRun} — a resumed run can never silently
 * continue under a changed definition (review-21 run-identity pinning).
 */
public class ResumeRejectedException extends RuntimeException {

    public ResumeRejectedException(String message) {
        super(message);
    }
}
