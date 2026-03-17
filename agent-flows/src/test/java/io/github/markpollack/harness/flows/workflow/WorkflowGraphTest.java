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
package io.github.markpollack.harness.flows.workflow;

import io.github.markpollack.harness.flows.Step;
import io.github.markpollack.harness.patterns.graph.NodeMetrics;
import io.github.markpollack.harness.patterns.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowGraphTest {

    private final Step<String, String> dummyStep = Step.named("dummy", (ctx, in) -> in);

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void shouldBuildGraphWithThreeNodesAndTwoEdges() {
        WorkflowNode fetchNode = WorkflowNode.deterministic("fetch", dummyStep);
        WorkflowNode analyzeNode = WorkflowNode.agent("analyze", dummyStep);
        WorkflowNode reportNode = WorkflowNode.deterministic("report", dummyStep);

        WorkflowEdge edge1 = WorkflowEdge.of("fetch", "analyze");
        WorkflowEdge edge2 = WorkflowEdge.of("analyze", "report");

        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "pr-review",
                List.of(fetchNode, analyzeNode, reportNode),
                List.of(edge1, edge2),
                "fetch",
                "report"
        );

        assertThat(graph.name()).isEqualTo("pr-review");
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.startNode()).isEqualTo("fetch");
        assertThat(graph.finishNode()).isEqualTo("report");
        assertThat(graph.metrics()).isEmpty();
    }

    @Test
    void nodesShouldBeImmutable() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test", List.of(WorkflowNode.deterministic("a", dummyStep)),
                List.of(), "a", "a"
        );

        assertThat(graph.nodes()).isUnmodifiable();
    }

    @Test
    void edgesShouldBeImmutable() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(WorkflowNode.deterministic("a", dummyStep)),
                List.of(WorkflowEdge.of("a", "a")),
                "a", "a"
        );

        assertThat(graph.edges()).isUnmodifiable();
    }

    // -------------------------------------------------------------------------
    // withMetrics
    // -------------------------------------------------------------------------

    @Test
    void withMetricsShouldProduceNewRecordOriginalUnchanged() {
        WorkflowGraph<String, String> original = WorkflowGraph.of(
                "test",
                List.of(WorkflowNode.deterministic("a", dummyStep)),
                List.of(), "a", "a"
        );

        Map<String, NodeMetrics> metricsMap = Map.of(
                "a", NodeMetrics.deterministic(Duration.ofMillis(100))
        );
        WorkflowGraph<String, String> enriched = original.withMetrics(metricsMap);

        assertThat(original.metrics()).isEmpty();
        assertThat(enriched.metrics()).hasSize(1);
        assertThat(enriched.metrics().get("a").duration()).isEqualTo(Duration.ofMillis(100));
        assertThat(enriched.name()).isEqualTo(original.name());
        assertThat(enriched.nodes()).isEqualTo(original.nodes());
    }

    // -------------------------------------------------------------------------
    // findNode / edgesFrom
    // -------------------------------------------------------------------------

    @Test
    void findNodeShouldReturnNodeByName() {
        WorkflowNode node = WorkflowNode.agent("analyze", dummyStep);
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test", List.of(node), List.of(), "analyze", "analyze"
        );

        assertThat(graph.findNode("analyze")).contains(node);
        assertThat(graph.findNode("missing")).isEmpty();
    }

    @Test
    void edgesFromShouldReturnOutgoingEdges() {
        WorkflowEdge e1 = WorkflowEdge.of("a", "b");
        WorkflowEdge e2 = WorkflowEdge.of("a", "c");
        WorkflowEdge e3 = WorkflowEdge.of("b", "c");

        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(
                        WorkflowNode.deterministic("a", dummyStep),
                        WorkflowNode.deterministic("b", dummyStep),
                        WorkflowNode.deterministic("c", dummyStep)
                ),
                List.of(e1, e2, e3),
                "a", "c"
        );

        assertThat(graph.edgesFrom("a")).containsExactly(e1, e2);
        assertThat(graph.edgesFrom("b")).containsExactly(e3);
        assertThat(graph.edgesFrom("c")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // WorkflowNode
    // -------------------------------------------------------------------------

    @Test
    void deterministicNodeShouldHaveCorrectType() {
        WorkflowNode node = WorkflowNode.deterministic("fetch", dummyStep);

        assertThat(node.name()).isEqualTo("fetch");
        assertThat(node.type()).isEqualTo(NodeType.DETERMINISTIC);
        assertThat(node.step()).isEqualTo(dummyStep);
    }

    @Test
    void agentNodeShouldHaveCorrectType() {
        WorkflowNode node = WorkflowNode.agent("analyze", dummyStep);

        assertThat(node.type()).isEqualTo(NodeType.AGENT);
    }

    // -------------------------------------------------------------------------
    // WorkflowEdge
    // -------------------------------------------------------------------------

    @Test
    void unconditionalEdgeShouldAlwaysMatch() {
        WorkflowEdge edge = WorkflowEdge.of("a", "b");

        assertThat(edge.isUnconditional()).isTrue();
        assertThat(edge.matches("anything")).isTrue();
        assertThat(edge.condition()).isNull();
        assertThat(edge.transform()).isNull();
    }

    @Test
    void conditionalEdgeShouldMatchWhenPredicateTrue() {
        WorkflowEdge edge = WorkflowEdge.conditional("a", "b", o -> "pass".equals(o));

        assertThat(edge.isUnconditional()).isFalse();
        assertThat(edge.matches("pass")).isTrue();
        assertThat(edge.matches("fail")).isFalse();
    }

    @Test
    void labeledEdgeShouldCarryLabel() {
        WorkflowEdge edge = WorkflowEdge.labeled("a", "b", "on-success");

        assertThat(edge.label()).isEqualTo("on-success");
        assertThat(edge.isUnconditional()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Static factory
    // -------------------------------------------------------------------------

    @Test
    void ofFactoryShouldCreateGraphWithEmptyMetrics() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "simple",
                List.of(WorkflowNode.deterministic("only", dummyStep)),
                List.of(),
                "only", "only"
        );

        assertThat(graph.metrics()).isEmpty();
    }
}
