package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.markpollack.workflow.spec.EdgeConditionSpec;
import io.github.markpollack.workflow.spec.RetryPolicySpec;
import io.github.markpollack.workflow.spec.WorkflowEdgeSpec;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-run constructor of the twelve required event types (alpha spec §9), owning the
 * run's monotonic sequence: one factory instance per {@code workflowRunId}; every event
 * it mints gets the next sequence number. Sequences are 1-based — {@code 0} is reserved
 * to mean "no event committed yet" (the durable checkpoint's {@code lastEventSequence}
 * proof, DESIGN.md § CheckpointStore).
 *
 * <p>Payload maps carry the §9 required fields using §6 wire vocabulary (lowercase
 * status names, spec-shaped policy/condition fragments via the NON_NULL mapper — no
 * nulls, no Java-isms). The payload key names here are the implementation the Step 2.5
 * freeze reconciles against §9's prose; until then this factory is the de-facto payload
 * contract.
 *
 * <p>{@code timestamp} comes from the injected {@link Clock} and is excluded from the
 * deterministic conformance projection; the {@code attributes} bag stays absent until
 * DD-19 defines it at 2.5.
 */
public final class WorkflowEventFactory {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String workflowRunId;
    private final String workflowSpecRef;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong(0);

    public WorkflowEventFactory(String workflowRunId, String workflowSpecRef) {
        this(workflowRunId, workflowSpecRef, Clock.systemUTC());
    }

    public WorkflowEventFactory(String workflowRunId, String workflowSpecRef, Clock clock) {
        this.workflowRunId = Objects.requireNonNull(workflowRunId, "workflowRunId");
        this.workflowSpecRef = Objects.requireNonNull(workflowSpecRef, "workflowSpecRef");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The sequence of the most recently minted event; 0 if none yet. */
    public long lastSequence() {
        return sequence.get();
    }

    public WorkflowEvent workflowStarted(String workflowName, String entrypoint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowName", Objects.requireNonNull(workflowName, "workflowName"));
        payload.put("entrypoint", Objects.requireNonNull(entrypoint, "entrypoint"));
        return event(WorkflowEventType.WORKFLOW_STARTED, null, null, null, payload);
    }

    public WorkflowEvent nodeStarted(String nodeId, String nodeKind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeKind", Objects.requireNonNull(nodeKind, "nodeKind"));
        return event(WorkflowEventType.NODE_STARTED, requireNodeId(nodeId), null, null, payload);
    }

    public WorkflowEvent operationDispatched(String nodeId, String operationRef, int attemptNumber) {
        return event(WorkflowEventType.OPERATION_DISPATCHED, requireNodeId(nodeId),
                requireRef(operationRef), requireAttempt(attemptNumber), null);
    }

    public WorkflowEvent operationSucceeded(String nodeId, String operationRef, int attemptNumber,
            ValueDisclosure outputDisclosure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putDisclosure(payload, "outputDisclosure", outputDisclosure);
        return event(WorkflowEventType.OPERATION_SUCCEEDED, requireNodeId(nodeId),
                requireRef(operationRef), requireAttempt(attemptNumber), payload.isEmpty() ? null : payload);
    }

    /**
     * Per-attempt failure event (§17 rule 6): accepts only {@code failure} or
     * {@code timed_out} results — {@code cancelled}/{@code aborted} terminate the
     * workflow and never produce {@code OperationFailed}.
     */
    public WorkflowEvent operationFailed(String nodeId, String operationRef, int attemptNumber,
            OperationResult result) {
        Objects.requireNonNull(result, "result");
        ErrorEnvelope error = switch (result) {
            case OperationResult.Failure f -> f.error();
            case OperationResult.TimedOut t -> t.error();
            default -> throw new IllegalArgumentException(
                    "OperationFailed is only emitted for failure/timed_out results, got: " + result.status());
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resultState", result.status().wireName());
        payload.put("error", WorkflowEventJson.mapper().convertValue(error, MAP_TYPE));
        return event(WorkflowEventType.OPERATION_FAILED, requireNodeId(nodeId),
                requireRef(operationRef), requireAttempt(attemptNumber), payload);
    }

    /**
     * Retry decision event: {@code attemptNumber} is the attempt that just failed;
     * {@code delayMillis} is the closed-form backoff value for it; {@code policy} is
     * the effective (precedence-resolved) retry policy snapshot.
     */
    public WorkflowEvent retryScheduled(String nodeId, String operationRef, int attemptNumber,
            String reason, long delayMillis, RetryPolicySpec policy) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must be >= 0: " + delayMillis);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", Objects.requireNonNull(reason, "reason"));
        payload.put("delayMillis", delayMillis);
        payload.put("policy", WorkflowEventJson.mapper()
                .convertValue(Objects.requireNonNull(policy, "policy"), MAP_TYPE));
        return event(WorkflowEventType.RETRY_SCHEDULED, requireNodeId(nodeId),
                requireRef(operationRef), requireAttempt(attemptNumber), payload);
    }

    /**
     * Binding evaluation event; on failure ({@code success == false}) the disclosure is
     * omitted — a failed binding has no value to disclose (§13).
     */
    public WorkflowEvent bindingEvaluated(String nodeId, String bindingTarget, String source,
            boolean success, ValueDisclosure valueDisclosure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bindingTarget", Objects.requireNonNull(bindingTarget, "bindingTarget"));
        payload.put("source", Objects.requireNonNull(source, "source"));
        payload.put("status", success ? "success" : "failure");
        putDisclosure(payload, "valueDisclosure", valueDisclosure);
        return event(WorkflowEventType.BINDING_EVALUATED, requireNodeId(nodeId), null, null, payload);
    }

    public WorkflowEvent contextWriteApplied(String nodeId, String contextKey, String source,
            ValueDisclosure valueDisclosure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contextKey", Objects.requireNonNull(contextKey, "contextKey"));
        payload.put("source", Objects.requireNonNull(source, "source"));
        putDisclosure(payload, "valueDisclosure", valueDisclosure);
        return event(WorkflowEventType.CONTEXT_WRITE_APPLIED, requireNodeId(nodeId), null, null, payload);
    }

    /** Edge selection; the envelope's {@code nodeId} is the edge's source node. */
    public WorkflowEvent edgeSelected(WorkflowEdgeSpec edge, String reason) {
        Objects.requireNonNull(edge, "edge");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", edge.from());
        payload.put("to", edge.to());
        payload.put("condition", conditionMap(edge.when()));
        payload.put("reason", Objects.requireNonNull(reason, "reason"));
        return event(WorkflowEventType.EDGE_SELECTED, edge.from(), null, null, payload);
    }

    public WorkflowEvent nodeCompleted(String nodeId, String state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", Objects.requireNonNull(state, "state"));
        return event(WorkflowEventType.NODE_COMPLETED, requireNodeId(nodeId), null, null, payload);
    }

    public WorkflowEvent workflowCompleted(String terminalState, ValueDisclosure outputDisclosure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("terminalState", Objects.requireNonNull(terminalState, "terminalState"));
        putDisclosure(payload, "outputDisclosure", outputDisclosure);
        return event(WorkflowEventType.WORKFLOW_COMPLETED, null, null, null, payload);
    }

    public WorkflowEvent workflowFailed(String terminalState, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("terminalState", Objects.requireNonNull(terminalState, "terminalState"));
        payload.put("reason", Objects.requireNonNull(reason, "reason"));
        return event(WorkflowEventType.WORKFLOW_FAILED, null, null, null, payload);
    }

    private WorkflowEvent event(WorkflowEventType type, String nodeId, String operationRef,
            Integer attemptNumber, Map<String, Object> payload) {
        return new WorkflowEvent(type, workflowRunId, workflowSpecRef, sequence.incrementAndGet(),
                clock.instant(), nodeId, operationRef, attemptNumber, payload, null);
    }

    private Map<String, Object> conditionMap(EdgeConditionSpec condition) {
        return WorkflowEventJson.mapper().convertValue(condition, MAP_TYPE);
    }

    private static void putDisclosure(Map<String, Object> payload, String key, ValueDisclosure disclosure) {
        if (disclosure != null) {
            payload.put(key, WorkflowEventJson.mapper().convertValue(disclosure, MAP_TYPE));
        }
    }

    private static String requireNodeId(String nodeId) {
        return Objects.requireNonNull(nodeId, "nodeId");
    }

    private static String requireRef(String operationRef) {
        return Objects.requireNonNull(operationRef, "operationRef");
    }

    private static Integer requireAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1: " + attemptNumber);
        }
        return attemptNumber;
    }
}
