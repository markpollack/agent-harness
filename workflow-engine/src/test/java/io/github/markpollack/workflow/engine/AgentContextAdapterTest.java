package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * String-keyed IR access and typed {@link ContextKey} access must hit the same
 * entries (name-only key equality), and the adapter must preserve AgentContext's
 * immutability.
 */
class AgentContextAdapterTest {

    private static final ContextKey<String> DIFF = ContextKey.of("pr.diff", String.class);

    @Test
    void stringKeyedReadSeesTypedWrite() {
        AgentContext ctx = AgentContext.withRunId("run-1")
                .mutate().with(DIFF, "the diff").build();

        AgentContextAdapter store = new AgentContextAdapter(ctx);

        assertThat(store.get("pr.diff")).contains("the diff");
        assertThat(store.get("workflowRunId")).contains("run-1");
    }

    @Test
    void typedReadSeesStringKeyedWrite() {
        AgentContextAdapter store = new AgentContextAdapter(AgentContext.withRunId("run-1"))
                .put("pr.diff", "the diff");

        assertThat(store.context().get(DIFF)).contains("the diff");
        assertThat(store.context().runId()).isEqualTo("run-1");
    }

    @Test
    void putReturnsNewStoreLeavingOriginalUnchanged() {
        AgentContextAdapter original = new AgentContextAdapter(AgentContext.withRunId("run-1"));

        AgentContextAdapter updated = original.put("verdict", "approved");

        assertThat(original.get("verdict")).isEmpty();
        assertThat(updated.get("verdict")).contains("approved");
        assertThat(updated).isNotSameAs(original);
    }

    @Test
    void missingKeyIsEmptyAndNullsAreRejected() {
        AgentContextAdapter store = new AgentContextAdapter(AgentContext.withRunId("run-1"));

        assertThat(store.get("absent")).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> store.put("k", null));
        assertThatNullPointerException().isThrownBy(() -> store.put(null, "v"));
        assertThatNullPointerException().isThrownBy(() -> store.get(null));
    }
}
