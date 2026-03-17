/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.harness.flows;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentContextTest {

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    @Test
    void createShouldGenerateRandomRunId() {
        AgentContext ctx = AgentContext.create();

        assertThat(ctx.runId()).isNotBlank();
    }

    @Test
    void withRunIdShouldSetSpecificRunId() {
        AgentContext ctx = AgentContext.withRunId("my-run-42");

        assertThat(ctx.runId()).isEqualTo("my-run-42");
    }

    // -------------------------------------------------------------------------
    // Type-safe access via ContextKey
    // -------------------------------------------------------------------------

    @Test
    void getShouldReturnValueForPresentKey() {
        ContextKey<String> key = ContextKey.of("test", String.class);
        AgentContext ctx = AgentContext.create().mutate()
                .with(key, "hello")
                .build();

        assertThat(ctx.get(key)).contains("hello");
    }

    @Test
    void getShouldReturnEmptyForAbsentKey() {
        ContextKey<String> key = ContextKey.of("missing", String.class);
        AgentContext ctx = AgentContext.create();

        assertThat(ctx.get(key)).isEmpty();
    }

    @Test
    void requireShouldReturnValueForPresentKey() {
        ContextKey<Integer> key = ContextKey.of("count", Integer.class);
        AgentContext ctx = AgentContext.create().mutate()
                .with(key, 42)
                .build();

        assertThat(ctx.require(key)).isEqualTo(42);
    }

    @Test
    void requireShouldThrowForAbsentKey() {
        ContextKey<String> key = ContextKey.of("missing", String.class);
        AgentContext ctx = AgentContext.create();

        assertThatThrownBy(() -> ctx.require(key))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("missing");
    }

    // -------------------------------------------------------------------------
    // Framework well-known keys
    // -------------------------------------------------------------------------

    @Test
    void workflowRunIdShouldBeAccessibleViaTypedKey() {
        AgentContext ctx = AgentContext.withRunId("typed-test");

        assertThat(ctx.get(AgentContext.WORKFLOW_RUN_ID)).contains("typed-test");
    }

    @Test
    void accumulatedCostShouldBeSettableAndReadable() {
        AgentContext ctx = AgentContext.create().mutate()
                .with(AgentContext.ACCUMULATED_COST, 5.67)
                .build();

        assertThat(ctx.require(AgentContext.ACCUMULATED_COST)).isEqualTo(5.67);
    }

    // -------------------------------------------------------------------------
    // Immutable mutation
    // -------------------------------------------------------------------------

    @Test
    void mutateShouldProduceNewInstanceWithoutModifyingOriginal() {
        ContextKey<String> key = ContextKey.of("data", String.class);
        AgentContext original = AgentContext.create();
        AgentContext modified = original.mutate()
                .with(key, "added")
                .build();

        assertThat(original.get(key)).isEmpty();
        assertThat(modified.get(key)).contains("added");
    }

    @Test
    void mutateShouldPreserveExistingEntries() {
        ContextKey<String> key1 = ContextKey.of("first", String.class);
        ContextKey<String> key2 = ContextKey.of("second", String.class);

        AgentContext ctx = AgentContext.create().mutate()
                .with(key1, "one")
                .build();

        AgentContext ctx2 = ctx.mutate()
                .with(key2, "two")
                .build();

        assertThat(ctx2.get(key1)).contains("one");
        assertThat(ctx2.get(key2)).contains("two");
    }

    @Test
    void mutateShouldAllowOverwritingExistingKey() {
        ContextKey<String> key = ContextKey.of("value", String.class);
        AgentContext ctx = AgentContext.create().mutate()
                .with(key, "old")
                .build();

        AgentContext updated = ctx.mutate()
                .with(key, "new")
                .build();

        assertThat(updated.require(key)).isEqualTo("new");
        assertThat(ctx.require(key)).isEqualTo("old");
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Test
    void toStringShouldIncludeRunId() {
        AgentContext ctx = AgentContext.withRunId("display-test");

        assertThat(ctx.toString()).contains("display-test");
    }
}
