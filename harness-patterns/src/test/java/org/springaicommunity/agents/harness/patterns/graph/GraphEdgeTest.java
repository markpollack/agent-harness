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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphEdgeTest {

    @Test
    void unconditionalEdge_alwaysMatches() {
        // Given
        GraphEdge<String> edge = GraphEdge.to("target");

        // Then
        assertThat(edge.matches("any value")).isTrue();
        assertThat(edge.matches("")).isTrue();
        assertThat(edge.matches(null)).isTrue();
    }

    @Test
    void edge_hasCorrectTarget() {
        // Given
        GraphEdge<String> edge = GraphEdge.to("my-target");

        // Then
        assertThat(edge.targetNodeName()).isEqualTo("my-target");
    }

    @Test
    void conditionalEdge_matchesWhenConditionTrue() {
        // Given
        GraphEdge<Integer> edge = GraphEdge.<Integer>to("target").when(n -> n > 0);

        // Then
        assertThat(edge.matches(5)).isTrue();
        assertThat(edge.matches(0)).isFalse();
        assertThat(edge.matches(-1)).isFalse();
    }

    @Test
    void conditionalEdge_preservesTarget() {
        // Given
        GraphEdge<String> original = GraphEdge.to("target");
        GraphEdge<String> conditional = original.when(s -> !s.isEmpty());

        // Then
        assertThat(conditional.targetNodeName()).isEqualTo("target");
    }

    @Test
    void transformer_transformsOutput() {
        // Given
        GraphEdge<Integer> edge = GraphEdge.<Integer>to("target")
                .transform(n -> "Number: " + n);

        // When
        Object result = edge.transformOutput(42);

        // Then
        assertThat(result).isEqualTo("Number: 42");
    }

    @Test
    void transformer_defaultsToIdentity() {
        // Given
        GraphEdge<String> edge = GraphEdge.to("target");

        // When
        Object result = edge.transformOutput("original");

        // Then
        assertThat(result).isEqualTo("original");
    }

    @Test
    void edge_canCombineConditionAndTransformer() {
        // Given
        GraphEdge<Integer> edge = GraphEdge.<Integer>to("target")
                .when(n -> n > 0)
                .transform(n -> n * 2);

        // When/Then
        assertThat(edge.matches(5)).isTrue();
        assertThat(edge.matches(-1)).isFalse();
        assertThat(edge.transformOutput(5)).isEqualTo(10);
    }

    @Test
    void edge_requiresTarget() {
        assertThatThrownBy(() -> GraphEdge.to(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void edge_toString() {
        // Given
        GraphEdge<String> edge = GraphEdge.to("my-target");

        // Then
        assertThat(edge.toString()).contains("my-target");
    }

    @Test
    void conditionalEdge_withRecordType() {
        // Given
        record TestResult(boolean passed, String message) {}
        GraphEdge<TestResult> passEdge = GraphEdge.<TestResult>to("next")
                .when(TestResult::passed);
        GraphEdge<TestResult> failEdge = GraphEdge.<TestResult>to("retry")
                .when(r -> !r.passed());

        TestResult success = new TestResult(true, "ok");
        TestResult failure = new TestResult(false, "error");

        // Then
        assertThat(passEdge.matches(success)).isTrue();
        assertThat(passEdge.matches(failure)).isFalse();
        assertThat(failEdge.matches(success)).isFalse();
        assertThat(failEdge.matches(failure)).isTrue();
    }
}
