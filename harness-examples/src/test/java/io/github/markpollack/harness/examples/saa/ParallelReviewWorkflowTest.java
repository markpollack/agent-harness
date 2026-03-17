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
package io.github.markpollack.harness.examples.saa;

import io.github.markpollack.harness.flows.Step;
import io.github.markpollack.harness.flows.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transliteration of Spring AI Alibaba's {@code StateGraphParallelTest} pattern.
 *
 * <h2>SAA equivalent (collapsed)</h2>
 * <pre>
 * StateGraph graph = new StateGraph(AgentState::new);
 * graph.addNode("review", state -> Map.of("review", agentA.chat(state)));
 * graph.addNode("audit",  state -> Map.of("audit",  agentB.chat(state)));
 * graph.addEdge(START, new String[]{"review", "audit"});  // fan-out
 * graph.addEdge(new String[]{"review", "audit"}, END);    // AND-join
 * graph.compile().invoke(...);
 * </pre>
 *
 * <h2>Our DSL equivalent</h2>
 * <pre>
 * Workflow.define("parallel-review")
 *     .parallel(reviewStep, auditStep)
 *     .run(input);
 * </pre>
 *
 * SAA requires explicit START/END markers and two separate {@code addEdge} calls
 * for fan-out and join. Our {@code parallel()} expresses fan-out + AND-join in
 * a single combinator. Result is {@code List<Object>} in declaration order.
 */
class ParallelReviewWorkflowTest {

    @Test
    @SuppressWarnings("unchecked")
    void parallelShouldFanOutAndJoinBothResults() {
        // SAA: graph.addNode("review", ...) + graph.addNode("audit", ...) + fan-out + join
        // Our DSL: .parallel(reviewStep, auditStep)
        Step<String, String> reviewStep = Step.named("review", (ctx, in) -> "review:" + in);
        Step<String, String> auditStep  = Step.named("audit",  (ctx, in) -> "audit:"  + in);

        List<Object> result = (List<Object>) Workflow.<String, Object>define("parallel-review")
                .parallel(reviewStep, auditStep)
                .run("pr-123");

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder("review:pr-123", "audit:pr-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parallelShouldExecuteBothSteps() {
        // Verify that both steps actually execute (not short-circuited)
        AtomicBoolean reviewRan = new AtomicBoolean(false);
        AtomicBoolean auditRan  = new AtomicBoolean(false);

        Step<String, String> reviewStep = Step.named("review", (ctx, in) -> {
            reviewRan.set(true);
            return "review-done";
        });
        Step<String, String> auditStep = Step.named("audit", (ctx, in) -> {
            auditRan.set(true);
            return "audit-done";
        });

        Workflow.<String, Object>define("parallel-review")
                .parallel(reviewStep, auditStep)
                .run("input");

        assertThat(reviewRan.get()).isTrue();
        assertThat(auditRan.get()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void parallelResultsShouldBePassableToNextStep() {
        // Demonstrates that the List<Object> join result can feed into a subsequent step
        Step<String, String> reviewStep = Step.named("review", (ctx, in) -> "r");
        Step<String, String> auditStep  = Step.named("audit",  (ctx, in) -> "a");

        @SuppressWarnings("unchecked")
        String summary = (String) Workflow.<String, Object>define("parallel-review")
                .parallel(reviewStep, auditStep)
                .then(Step.named("summarize", (ctx, in) -> {
                    List<Object> parts = (List<Object>) in;
                    return String.join("+", parts.stream().map(Object::toString).toList());
                }))
                .run("input");

        assertThat(summary).isEqualTo("r+a");
    }
}
