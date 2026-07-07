package io.github.markpollack.workflow.spec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** Cheap local invariants and value semantics of the sealed model. */
class WorkflowSpecModelTest {

    @Test
    void requiredFieldsAreEnforcedAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskSpecNode(null, null, null, null, "op", null, null, null))
                .withMessageContaining("id");
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskSpecNode("t", null, null, null, null, null, null, null))
                .withMessageContaining("operation");
        assertThatNullPointerException()
                .isThrownBy(() -> new Binding(null))
                .withMessageContaining("from");
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkflowEdgeSpec("a", "b", null, null))
                .withMessageContaining("when");
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkflowMetadata(null, null, null, null))
                .withMessageContaining("name");
    }

    @Test
    void decisionNodeRequiresAtLeastOneOutcome() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DecisionSpecNode("d", null, null, null, "op", null, List.of(), null))
                .withMessageContaining("outcome");
    }

    @Test
    void errorMatchRequiresAtLeastOneCriterion() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ErrorMatch(null, null))
                .withMessageContaining("at least one");
        assertThat(new ErrorMatch("RATE_LIMIT", null).code()).isEqualTo("RATE_LIMIT");
        assertThat(new ErrorMatch(null, true).retryable()).isTrue();
    }

    @Test
    void bindingRejectsNonDollarSources() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Binding("input.url"))
                .withMessageContaining("$");
    }

    @Test
    void retryPolicyEnforcesBounds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicySpec(0, null, null))
                .withMessageContaining("maxAttempts");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicySpec(3, null, List.of()))
                .withMessageContaining("retryOn");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TimeoutPolicySpec(0))
                .withMessageContaining("perAttemptMillis");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BackoffSpec(BackoffSpec.Strategy.FIXED, -1, null, null))
                .withMessageContaining("initialMillis");
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        List<String> outcomes = new ArrayList<>(List.of("a", "b"));
        DecisionSpecNode node = new DecisionSpecNode("d", null, null, null, "op", null, outcomes, null);

        outcomes.add("c");

        assertThat(node.outcomes()).containsExactly("a", "b");
    }

    @Test
    void identicallyConstructedSpecsAreEqual() {
        assertThat(minimalSpec()).isEqualTo(minimalSpec());
        assertThat(minimalSpec().hashCode()).isEqualTo(minimalSpec().hashCode());
    }

    @Test
    void unsupportedApiVersionOrKindRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowSpec("workflow/v1", WorkflowSpec.KIND,
                        new WorkflowMetadata("m", null, null, null),
                        null, null, null,
                        Map.of("op", new OperationDeclaration("java:x:v1", null, null, null, null, null)),
                        List.of(new TaskSpecNode("t", null, null, null, "op", null, null, null)),
                        List.of(), null, "t", null))
                .withMessageContaining("apiVersion");
    }

    private static WorkflowSpec minimalSpec() {
        return new WorkflowSpec(
                WorkflowSpec.API_VERSION,
                WorkflowSpec.KIND,
                new WorkflowMetadata("minimal", null, null, null),
                null, null, null,
                Map.of("op", new OperationDeclaration("java:test.op:v1", null, null, null, null, null)),
                List.of(new TaskSpecNode("only", null, null, null, "op", null, null, null)),
                List.of(),
                null,
                "only",
                null);
    }
}
