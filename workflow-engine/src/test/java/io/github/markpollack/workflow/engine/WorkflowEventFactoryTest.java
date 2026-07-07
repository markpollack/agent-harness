package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.spec.AlwaysCondition;
import io.github.markpollack.workflow.spec.BackoffSpec;
import io.github.markpollack.workflow.spec.DecisionResultCondition;
import io.github.markpollack.workflow.spec.ErrorMatcher;
import io.github.markpollack.workflow.spec.RetryPolicySpec;
import io.github.markpollack.workflow.spec.WorkflowEdgeSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * §9 payload conformance and §10 monotonic sequencing for all twelve required event
 * types. Payload key names asserted here are the de-facto contract the Step 2.5 freeze
 * reconciles.
 */
class WorkflowEventFactoryTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
    private static final String SPEC_REF = "workflow://registry/pr-review@1.0.0";

    private final WorkflowEventFactory factory =
            new WorkflowEventFactory("run-1", SPEC_REF, FIXED_CLOCK);

    @Test
    void envelopeCommonFieldsOnEveryEvent() {
        WorkflowEvent event = factory.workflowStarted("pr-review", "fetch");

        assertThat(event.eventType()).isEqualTo(WorkflowEventType.WORKFLOW_STARTED);
        assertThat(event.workflowRunId()).isEqualTo("run-1");
        assertThat(event.workflowSpecRef()).isEqualTo(SPEC_REF);
        assertThat(event.sequence()).isEqualTo(1);
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-07-06T12:00:00Z"));
        assertThat(event.attributes()).isNull();
    }

    @Test
    void sequencesAreMonotonicAndOneBased() {
        List<WorkflowEvent> events = List.of(
                factory.workflowStarted("pr-review", "fetch"),
                factory.nodeStarted("fetch", "task"),
                factory.operationDispatched("fetch", "java:fetch-diff", 1),
                factory.operationSucceeded("fetch", "java:fetch-diff", 1,
                        ValueDisclosure.metadataOnly("diff text")),
                factory.nodeCompleted("fetch", "succeeded"),
                factory.workflowCompleted("completed", null));

        assertThat(events).extracting(WorkflowEvent::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(factory.lastSequence()).isEqualTo(6);
    }

    @Test
    void workflowStartedCarriesMetadataReferenceAndEntrypoint() {
        WorkflowEvent event = factory.workflowStarted("pr-review", "fetch");

        assertThat(event.payload())
                .containsEntry("workflowName", "pr-review")
                .containsEntry("entrypoint", "fetch");
    }

    @Test
    void nodeStartedCarriesNodeKind() {
        WorkflowEvent event = factory.nodeStarted("route", "decision");

        assertThat(event.nodeId()).isEqualTo("route");
        assertThat(event.payload()).containsEntry("nodeKind", "decision");
    }

    @Test
    void operationDispatchedIsEnvelopeOnly() {
        WorkflowEvent event = factory.operationDispatched("fetch", "java:fetch-diff", 2);

        assertThat(event.nodeId()).isEqualTo("fetch");
        assertThat(event.operationRef()).isEqualTo("java:fetch-diff");
        assertThat(event.attemptNumber()).isEqualTo(2);
        assertThat(event.payload()).isNull();
    }

    @Test
    void operationSucceededCarriesOutputDisclosure() {
        WorkflowEvent event = factory.operationSucceeded("fetch", "java:fetch-diff", 1,
                ValueDisclosure.metadataOnly("hello"));

        assertThat(event.payload()).containsKey("outputDisclosure");
        @SuppressWarnings("unchecked")
        Map<String, Object> disclosure = (Map<String, Object>) event.payload().get("outputDisclosure");
        assertThat(disclosure)
                .containsEntry("mode", "metadata_only")
                .containsEntry("type", "string")
                .containsEntry("sizeBytes", 5L)
                .containsEntry("reason", "default_policy");
    }

    @Test
    void operationFailedCarriesStateAndErrorEnvelopeWithoutNulls() {
        OperationResult result = OperationResult.failure(
                ErrorEnvelope.of("RATE_LIMIT", "slow down", true));

        WorkflowEvent event = factory.operationFailed("fetch", "java:fetch-diff", 1, result);

        assertThat(event.payload()).containsEntry("resultState", "failure");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) event.payload().get("error");
        assertThat(error)
                .containsEntry("code", "RATE_LIMIT")
                .containsEntry("message", "slow down")
                .containsEntry("retryable", true)
                .doesNotContainKey("details");
    }

    @Test
    void timedOutResultReportsTimedOutWireState() {
        OperationResult result = OperationResult.timedOut(
                ErrorEnvelope.of("OPERATION_TIMEOUT", "exceeded 300000 ms", true));

        WorkflowEvent event = factory.operationFailed("fetch", "java:fetch-diff", 3, result);

        assertThat(event.payload()).containsEntry("resultState", "timed_out");
    }

    @Test
    void operationFailedRejectsNonRoutableStates() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                factory.operationFailed("fetch", "java:fetch-diff", 1,
                        OperationResult.success("out")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                factory.operationFailed("fetch", "java:fetch-diff", 1,
                        OperationResult.cancelled("stop")));
    }

    @Test
    void retryScheduledCarriesReasonDelayAndPolicySnapshot() {
        RetryPolicySpec policy = new RetryPolicySpec(3,
                new BackoffSpec(BackoffSpec.Strategy.EXPONENTIAL, 1000, 30000L, 2.0),
                List.of(new ErrorMatcher("RATE_LIMIT")));

        WorkflowEvent event = factory.retryScheduled("fetch", "java:fetch-diff", 1,
                "retryable_error_within_budget", 1000, policy);

        assertThat(event.payload())
                .containsEntry("reason", "retryable_error_within_budget")
                .containsEntry("delayMillis", 1000L);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) event.payload().get("policy");
        assertThat(snapshot).containsEntry("maxAttempts", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> backoff = (Map<String, Object>) snapshot.get("backoff");
        assertThat(backoff)
                .containsEntry("strategy", "exponential")
                .containsEntry("initialMillis", 1000L)
                .containsEntry("maxMillis", 30000L)
                .containsEntry("multiplier", 2.0);
        assertThat(snapshot.get("retryOn")).isEqualTo(List.of(Map.of("code", "RATE_LIMIT")));
    }

    @Test
    void bindingEvaluatedSuccessDisclosesMetadataOnly() {
        WorkflowEvent event = factory.bindingEvaluated("fetch", "url", "$input.url",
                true, ValueDisclosure.metadataOnly(Map.of("k", "v")));

        assertThat(event.payload())
                .containsEntry("bindingTarget", "url")
                .containsEntry("source", "$input.url")
                .containsEntry("status", "success")
                .containsKey("valueDisclosure");
    }

    @Test
    void bindingEvaluatedFailureOmitsDisclosure() {
        WorkflowEvent event = factory.bindingEvaluated("fetch", "url", "$input.url",
                false, null);

        assertThat(event.payload()).containsEntry("status", "failure");
        assertThat(event.payload()).doesNotContainKey("valueDisclosure");
    }

    @Test
    void contextWriteAppliedCarriesKeySourceAndDisclosure() {
        WorkflowEvent event = factory.contextWriteApplied("fetch", "pr.diff",
                "$node.fetch.output", ValueDisclosure.metadataOnly("diff"));

        assertThat(event.payload())
                .containsEntry("contextKey", "pr.diff")
                .containsEntry("source", "$node.fetch.output")
                .containsKey("valueDisclosure");
    }

    @Test
    void edgeSelectedCarriesEndpointsConditionAndReason() {
        WorkflowEdgeSpec edge = new WorkflowEdgeSpec("route", "post",
                new DecisionResultCondition("post"), null);

        WorkflowEvent event = factory.edgeSelected(edge, "decision_outcome_match");

        assertThat(event.nodeId()).isEqualTo("route");
        assertThat(event.payload())
                .containsEntry("from", "route")
                .containsEntry("to", "post")
                .containsEntry("reason", "decision_outcome_match");
        @SuppressWarnings("unchecked")
        Map<String, Object> condition = (Map<String, Object>) event.payload().get("condition");
        assertThat(condition)
                .containsEntry("kind", "decisionResult")
                .containsEntry("value", "post");
    }

    @Test
    void alwaysConditionSerializesWithKindDiscriminator() {
        WorkflowEdgeSpec edge = new WorkflowEdgeSpec("fetch", "review", new AlwaysCondition(), null);

        WorkflowEvent event = factory.edgeSelected(edge, "always");

        @SuppressWarnings("unchecked")
        Map<String, Object> condition = (Map<String, Object>) event.payload().get("condition");
        assertThat(condition).containsEntry("kind", "always").hasSize(1);
    }

    @Test
    void nodeCompletedAndTerminalWorkflowEvents() {
        WorkflowEvent node = factory.nodeCompleted("fetch", "succeeded");
        WorkflowEvent completed = factory.workflowCompleted("completed",
                ValueDisclosure.metadataOnly("final"));
        WorkflowEvent failed = factory.workflowFailed("failed", "retry_exhausted");

        assertThat(node.payload()).containsEntry("state", "succeeded");
        assertThat(completed.payload())
                .containsEntry("terminalState", "completed")
                .containsKey("outputDisclosure");
        assertThat(failed.payload())
                .containsEntry("terminalState", "failed")
                .containsEntry("reason", "retry_exhausted");
        assertThat(completed.nodeId()).isNull();
        assertThat(failed.nodeId()).isNull();
    }

    @Test
    void identityArgumentsAreValidated() {
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkflowEventFactory(null, SPEC_REF));
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkflowEventFactory("run-1", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.operationDispatched("fetch", "ref", 0));
    }
}
