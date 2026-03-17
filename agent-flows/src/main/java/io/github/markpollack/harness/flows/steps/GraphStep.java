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

import io.github.markpollack.harness.flows.AgentStepException;
import io.github.markpollack.harness.flows.Step;
import io.github.markpollack.harness.patterns.graph.GraphCompositionStrategy;
import io.github.markpollack.harness.patterns.graph.GraphResult;

/**
 * Bridge between {@link AgentFlow} and {@link GraphCompositionStrategy}.
 * <p>
 * {@code GraphStep} wraps a full graph as a single {@link Step}, enabling
 * composition of graphs inside an {@code AgentFlow} or nesting graphs within graphs.
 * This is the seam between the two composition abstractions.
 *
 * <pre>{@code
 * // Parallel independent graphs:
 * AgentFlow.parallel(
 *     GraphStep.of(prReviewGraph),
 *     GraphStep.of(depAuditGraph),
 *     GraphStep.of(docsGuardianGraph)
 * )
 *
 * // Two-phase sequential graphs (output of first feeds second):
 * AgentFlow.sequential(
 *     GraphStep.of(exploreGraph),
 *     GraphStep.of(refactorGraph)
 * )
 * }</pre>
 *
 * <h2>Dependency direction</h2>
 * <p>
 * {@code agent-flows} depends on {@code harness-patterns} (for {@code GraphStep}).
 * {@code GraphCompositionStrategy} remains unaware of {@code AgentFlow}.
 */
public final class GraphStep {

    private GraphStep() {
    }

    /**
     * Wraps a {@link GraphCompositionStrategy} as a {@link Step}.
     * <p>
     * If the graph fails (stuck in node, max iterations, or error), an
     * {@link AgentStepException} is thrown with the failure details.
     *
     * @param graph the graph to wrap
     * @param <I>   the graph's input type
     * @param <O>   the graph's output type
     * @return a Step that executes the graph
     */
    public static <I, O> Step<I, O> of(GraphCompositionStrategy<I, O> graph) {
        return Step.named("GraphStep[" + graph.name() + "]", (ctx, input) -> {
            GraphResult<O> result = graph.execute(input);
            if (result.isFailure()) {
                String detail = result.error() != null ? ": " + result.error().getMessage() : "";
                throw new AgentStepException(
                        "GraphStep '" + graph.name() + "' failed with status " + result.status() + detail);
            }
            return result.output();
        });
    }
}
