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
package io.github.markpollack.harness.flows.steps;

import io.github.markpollack.harness.flows.AgentContext;
import io.github.markpollack.harness.flows.AgentStep;
import io.github.markpollack.harness.flows.AgentStepException;
import io.github.markpollack.harness.patterns.graph.GraphCompositionStrategy;
import io.github.markpollack.harness.patterns.graph.GraphContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphStepTest {

    private final AgentContext ctx = AgentContext.create();

    @Test
    void ofShouldReturnGraphOutputOnSuccess() {
        GraphCompositionStrategy<String, String> graph = GraphCompositionStrategy.<String, String>builder("test-graph")
                .startNode("start")
                .node("transform", (GraphContext gCtx, String input) -> input.toUpperCase() + "!")
                .finishNode("finish")
                .edge("start").to("transform")
                .edge("transform").to("finish")
                .build();

        AgentStep<String, String> step = GraphStep.of(graph);

        assertThat(step.execute(ctx, "hello")).isEqualTo("HELLO!");
    }

    @Test
    void ofShouldThrowAgentStepExceptionOnGraphFailure() {
        // A graph that gets stuck (no edge from start, and start != finish)
        GraphCompositionStrategy<String, String> stuckGraph = GraphCompositionStrategy.<String, String>builder("stuck-graph")
                .startNode("start")
                .finishNode("finish")
                .build();  // no edge from start → will get stuck immediately

        AgentStep<String, String> step = GraphStep.of(stuckGraph);

        assertThatThrownBy(() -> step.execute(ctx, "test"))
                .isInstanceOf(AgentStepException.class)
                .hasMessageContaining("stuck-graph");
    }

    @Test
    void ofShouldPassInputThroughSimpleStartFinishGraph() {
        GraphCompositionStrategy<String, String> passThrough = GraphCompositionStrategy.<String, String>builder("pass-through")
                .startNode("start")
                .finishNode("finish")
                .edge("start").to("finish")
                .build();

        AgentStep<String, String> step = GraphStep.of(passThrough);

        assertThat(step.execute(ctx, "unchanged")).isEqualTo("unchanged");
    }
}
