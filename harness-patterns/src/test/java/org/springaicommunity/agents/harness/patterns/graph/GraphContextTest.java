/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.agents.harness.patterns.graph;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphContextTest {

    @Test
    void context_generatesRunId() {
        // Given
        GraphContext context = new GraphContext("test-graph");

        // Then
        assertThat(context.runId()).isNotNull();
        assertThat(context.runId()).isNotEmpty();
    }

    @Test
    void context_setsStartedAt() {
        // Given
        Instant before = Instant.now();
        GraphContext context = new GraphContext("test-graph");
        Instant after = Instant.now();

        // Then
        assertThat(context.startedAt()).isAfterOrEqualTo(before);
        assertThat(context.startedAt()).isBeforeOrEqualTo(after);
    }

    @Test
    void context_storesStrategyName() {
        // Given
        GraphContext context = new GraphContext("my-strategy");

        // Then
        assertThat(context.strategyName()).isEqualTo("my-strategy");
    }

    @Test
    void context_canUseCustomRunId() {
        // Given
        GraphContext context = new GraphContext("test-graph", "custom-run-123");

        // Then
        assertThat(context.runId()).isEqualTo("custom-run-123");
    }

    @Test
    void context_putAndGet() {
        // Given
        GraphContext context = new GraphContext("test-graph");

        // When
        context.put("key", "value");

        // Then
        Optional<String> result = context.get("key", String.class);
        assertThat(result).contains("value");
    }

    @Test
    void context_getReturnsEmptyForMissingKey() {
        // Given
        GraphContext context = new GraphContext("test-graph");

        // Then
        assertThat(context.get("missing", String.class)).isEmpty();
    }

    @Test
    void context_getReturnsEmptyForWrongType() {
        // Given
        GraphContext context = new GraphContext("test-graph");
        context.put("key", 42);

        // Then
        assertThat(context.get("key", String.class)).isEmpty();
    }

    @Test
    void context_getOrDefault() {
        // Given
        GraphContext context = new GraphContext("test-graph");
        context.put("exists", "value");

        // Then
        assertThat(context.getOrDefault("exists", String.class, "default")).isEqualTo("value");
        assertThat(context.getOrDefault("missing", String.class, "default")).isEqualTo("default");
    }

    @Test
    void context_remove() {
        // Given
        GraphContext context = new GraphContext("test-graph");
        context.put("key", "value");

        // When
        Object removed = context.remove("key");

        // Then
        assertThat(removed).isEqualTo("value");
        assertThat(context.get("key", String.class)).isEmpty();
    }

    @Test
    void context_containsKey() {
        // Given
        GraphContext context = new GraphContext("test-graph");
        context.put("exists", "value");

        // Then
        assertThat(context.containsKey("exists")).isTrue();
        assertThat(context.containsKey("missing")).isFalse();
    }

    @Test
    void context_requiresStrategyName() {
        assertThatThrownBy(() -> new GraphContext(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void context_toString() {
        // Given
        GraphContext context = new GraphContext("my-graph", "run-123");

        // Then
        assertThat(context.toString()).contains("run-123").contains("my-graph");
    }

    @Test
    void context_supportsMultipleTypes() {
        // Given
        GraphContext context = new GraphContext("test-graph");

        // When
        context.put("string", "hello");
        context.put("integer", 42);
        context.put("boolean", true);
        context.put("object", new Object());

        // Then
        assertThat(context.get("string", String.class)).contains("hello");
        assertThat(context.get("integer", Integer.class)).contains(42);
        assertThat(context.get("boolean", Boolean.class)).contains(true);
        assertThat(context.get("object", Object.class)).isPresent();
    }
}
