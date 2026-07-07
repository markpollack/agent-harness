package io.github.markpollack.workflow.engine;

/**
 * The five canonical attempt-outcome states (alpha spec §6). Deliberately closed: the
 * infra-vs-code distinction arrives as {@code error.origin} metadata at the Step 2.5
 * event freeze, never as a sixth state (Prefect/Argo teardown verdict).
 */
public enum OperationStatus {
    SUCCESS,
    FAILURE,
    TIMED_OUT,
    CANCELLED,
    ABORTED
}
