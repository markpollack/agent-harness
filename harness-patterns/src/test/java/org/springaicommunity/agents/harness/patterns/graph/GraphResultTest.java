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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphResultTest {

    @Test
    void completed_hasCorrectStatus() {
        // Given
        GraphResult<String> result = GraphResult.completed(
                "output",
                List.of("start", "middle", "finish"),
                3,
                Duration.ofMillis(100)
        );

        // Then
        assertThat(result.status()).isEqualTo(GraphResult.GraphStatus.COMPLETED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isFailure()).isFalse();
    }

    @Test
    void completed_hasOutput() {
        // Given
        GraphResult<String> result = GraphResult.completed(
                "my output",
                List.of("a", "b"),
                2,
                Duration.ofMillis(50)
        );

        // Then
        assertThat(result.output()).isEqualTo("my output");
    }

    @Test
    void completed_tracksPath() {
        // Given
        List<String> path = List.of("start", "process", "finish");
        GraphResult<String> result = GraphResult.completed("out", path, 3, Duration.ofMillis(100));

        // Then
        assertThat(result.pathTaken()).containsExactly("start", "process", "finish");
    }

    @Test
    void completed_tracksIterations() {
        // Given
        GraphResult<String> result = GraphResult.completed("out", List.of("a"), 5, Duration.ofMillis(100));

        // Then
        assertThat(result.iterations()).isEqualTo(5);
    }

    @Test
    void completed_tracksDuration() {
        // Given
        Duration duration = Duration.ofSeconds(2);
        GraphResult<String> result = GraphResult.completed("out", List.of("a"), 1, duration);

        // Then
        assertThat(result.duration()).isEqualTo(duration);
    }

    @Test
    void stuckInNode_hasCorrectStatus() {
        // Given
        GraphResult<String> result = GraphResult.stuckInNode(
                "broken-node",
                List.of("start", "broken-node"),
                2,
                Duration.ofMillis(100)
        );

        // Then
        assertThat(result.status()).isEqualTo(GraphResult.GraphStatus.STUCK_IN_NODE);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void stuckInNode_tracksNodeName() {
        // Given
        GraphResult<String> result = GraphResult.stuckInNode(
                "the-stuck-node",
                List.of("a", "the-stuck-node"),
                2,
                Duration.ofMillis(100)
        );

        // Then
        assertThat(result.stuckNodeName()).isEqualTo("the-stuck-node");
    }

    @Test
    void stuckInNode_hasNullOutput() {
        // Given
        GraphResult<String> result = GraphResult.stuckInNode("node", List.of("a"), 1, Duration.ofMillis(100));

        // Then
        assertThat(result.output()).isNull();
    }

    @Test
    void maxIterationsExceeded_hasCorrectStatus() {
        // Given
        GraphResult<String> result = GraphResult.maxIterationsExceeded(
                List.of("a", "b", "a", "b"),
                100,
                Duration.ofSeconds(5)
        );

        // Then
        assertThat(result.status()).isEqualTo(GraphResult.GraphStatus.MAX_ITERATIONS);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void error_hasCorrectStatus() {
        // Given
        Exception error = new RuntimeException("Something went wrong");
        GraphResult<String> result = GraphResult.error(
                error,
                List.of("start", "error-node"),
                2,
                Duration.ofMillis(100)
        );

        // Then
        assertThat(result.status()).isEqualTo(GraphResult.GraphStatus.ERROR);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void error_tracksException() {
        // Given
        Exception error = new IllegalStateException("Bad state");
        GraphResult<String> result = GraphResult.error(error, List.of("a"), 1, Duration.ofMillis(100));

        // Then
        assertThat(result.error()).isEqualTo(error);
        assertThat(result.error().getMessage()).isEqualTo("Bad state");
    }

    @Test
    void pathTaken_isImmutable() {
        // Given
        GraphResult<String> result = GraphResult.completed("out", List.of("a", "b"), 2, Duration.ofMillis(100));

        // Then
        assertThat(result.pathTaken()).isUnmodifiable();
    }

    @Test
    void toString_containsRelevantInfo() {
        // Given
        GraphResult<String> completed = GraphResult.completed("out", List.of("a", "b"), 2, Duration.ofMillis(100));
        GraphResult<String> stuck = GraphResult.stuckInNode("node", List.of("a", "node"), 2, Duration.ofMillis(100));
        GraphResult<String> error = GraphResult.error(new RuntimeException("oops"), List.of("a"), 1, Duration.ofMillis(100));

        // Then
        assertThat(completed.toString()).contains("COMPLETED").contains("iterations=2");
        assertThat(stuck.toString()).contains("STUCK_IN_NODE").contains("stuckIn=node");
        assertThat(error.toString()).contains("ERROR").contains("oops");
    }
}
