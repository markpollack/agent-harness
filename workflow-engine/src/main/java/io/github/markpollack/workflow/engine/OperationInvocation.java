package io.github.markpollack.workflow.engine;

import java.util.Objects;

/**
 * Execution identity carried through every dispatch (DESIGN.md Runner Adaptation Note).
 * Handlers receive it for logging, attribution, and idempotency keys — the natural
 * dedup key is {@code (workflowRunId, nodeId, attemptNumber)} or
 * {@code (workflowRunId, nodeId)} depending on the effect (Operation Contract).
 *
 * <p>{@code nodeId} is workflow-position identity, {@code operationRef} is capability
 * identity (DD-7) — the checkpoint key {@code (workflowRunId, nodeId)} is derived by
 * the interpreter itself and never includes {@code operationRef} or
 * {@code attemptNumber}.
 *
 * <p>{@code attemptNumber} is 1-based and counts the first attempt (the §4 policies
 * convention: {@code maxAttempts} includes attempt one).
 */
public record OperationInvocation(
        String workflowRunId,
        String nodeId,
        String operationRef,
        int attemptNumber) {

    public OperationInvocation {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(operationRef, "operationRef");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1: " + attemptNumber);
        }
    }
}
