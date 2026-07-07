package io.github.markpollack.workflow.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** Construction invariants of the dispatch identity record. */
class OperationInvocationTest {

    @Test
    void carriesAllIdentityComponents() {
        OperationInvocation invocation =
                new OperationInvocation("run-1", "review", "java:review-pr", 1);

        assertThat(invocation.workflowRunId()).isEqualTo("run-1");
        assertThat(invocation.nodeId()).isEqualTo("review");
        assertThat(invocation.operationRef()).isEqualTo("java:review-pr");
        assertThat(invocation.attemptNumber()).isEqualTo(1);
    }

    @Test
    void identityComponentsAreRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OperationInvocation(null, "n", "ref", 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new OperationInvocation("run", null, "ref", 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new OperationInvocation("run", "n", null, 1));
    }

    @Test
    void attemptNumberIsOneBased() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationInvocation("run", "n", "ref", 0))
                .withMessageContaining("attemptNumber");
    }
}
