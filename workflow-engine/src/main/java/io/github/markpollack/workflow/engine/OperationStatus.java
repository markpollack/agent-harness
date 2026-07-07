package io.github.markpollack.workflow.engine;

/**
 * The five canonical attempt-outcome states (alpha spec §6). Deliberately closed: the
 * infra-vs-code distinction arrives as {@code error.origin} metadata at the Step 2.5
 * event freeze, never as a sixth state (Prefect/Argo teardown verdict).
 *
 * <p><b>Wire names are NOT these constant names.</b> The §6 wire forms are lowercase
 * ({@code success}, {@code timed_out}, …); the JSON projection and its name mapping are
 * pinned at the Step 2.5 event freeze — never serialize this enum with default naming.
 */
public enum OperationStatus {
    SUCCESS,
    FAILURE,
    TIMED_OUT,
    CANCELLED,
    ABORTED
}
