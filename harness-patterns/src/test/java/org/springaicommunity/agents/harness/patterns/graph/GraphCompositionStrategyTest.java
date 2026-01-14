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

class GraphCompositionStrategyTest {

    @Test
    void linearGraph_executesInOrder() {
        // Given: start -> a -> b -> finish
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("linear")
                .startNode("start")
                .finishNode("finish")
                .node("a", (ctx, input) -> input + "-A")
                .node("b", (ctx, input) -> input + "-B")
                .edge("start").to("a")
                .edge("a").to("b")
                .edge("b").to("finish")
                .build();

        // When
        GraphResult<String> result = strategy.execute("input");

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("input-A-B");
        assertThat(result.pathTaken()).containsExactly("start", "a", "b", "finish");
    }

    @Test
    void simpleStartFinish_passesThrough() {
        // Given: start -> finish (pass-through)
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("simple")
                .startNode("start")
                .finishNode("finish")
                .edge("start").to("finish")
                .build();

        // When
        GraphResult<String> result = strategy.execute("hello");

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.pathTaken()).containsExactly("start", "finish");
    }

    @Test
    void conditionalEdge_routesBasedOnCondition() {
        // Given: start -> router -> (pass | fail) -> finish
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("conditional")
                .startNode("start")
                .finishNode("finish")
                .node("router", (ctx, input) -> input)
                .node("pass", (ctx, input) -> "PASSED: " + input)
                .node("fail", (ctx, input) -> "FAILED: " + input)
                .edge("start").to("router")
                .edge("router").to("pass").when((String s) -> s.contains("success"))
                .edge("router").to("fail").when((String s) -> !s.contains("success"))
                .edge("pass").to("finish")
                .edge("fail").to("finish")
                .build();

        // When
        GraphResult<String> passResult = strategy.execute("success");
        GraphResult<String> failResult = strategy.execute("failure");

        // Then
        assertThat(passResult.output()).isEqualTo("PASSED: success");
        assertThat(passResult.pathTaken()).contains("pass");
        assertThat(failResult.output()).isEqualTo("FAILED: failure");
        assertThat(failResult.pathTaken()).contains("fail");
    }

    @Test
    void cyclicGraph_iteratesUntilConditionMet() {
        // Given: start -> counter -> (finish if >= 3, else loop back)
        GraphCompositionStrategy<Integer, Integer> strategy = GraphCompositionStrategy.<Integer, Integer>builder("cyclic")
                .startNode("start")
                .finishNode("finish")
                .node("counter", (GraphContext ctx, Integer input) -> input + 1)
                .edge("start").to("counter")
                .edge("counter").to("finish").when((Integer n) -> n >= 3)
                .edge("counter").to("counter").when((Integer n) -> n < 3)
                .build();

        // When
        GraphResult<Integer> result = strategy.execute(0);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo(3);
        assertThat(result.pathTaken()).containsExactly("start", "counter", "counter", "counter", "finish");
    }

    @Test
    void maxIterations_stopsInfiniteLoop() {
        // Given: start -> loop (always loops back)
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("infinite")
                .startNode("start")
                .finishNode("finish")
                .node("loop", (ctx, input) -> input)
                .edge("start").to("loop").and()
                .edge("loop").to("loop").and() // Always loops
                .maxIterations(5)
                .build();

        // When
        GraphResult<String> result = strategy.execute("test");

        // Then
        assertThat(result.status()).isEqualTo(GraphResult.GraphStatus.MAX_ITERATIONS);
        assertThat(result.iterations()).isEqualTo(6); // 1 over max because check happens after increment
    }

    @Test
    void stuckNode_detectedWhenNoMatchingEdge() {
        // Given: start -> stuck (no matching edges)
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("stuck")
                .startNode("start")
                .finishNode("finish")
                .node("stuck", (ctx, input) -> "unexpected")
                .edge("start").to("stuck")
                .edge("stuck").to("finish").when((String s) -> s.equals("expected")) // Won't match
                .build();

        // When
        GraphResult<String> result = strategy.execute("test");

        // Then
        assertThat(result.status()).isEqualTo(GraphResult.GraphStatus.STUCK_IN_NODE);
        assertThat(result.stuckNodeName()).isEqualTo("stuck");
    }

    @Test
    void errorInNode_capturedInResult() {
        // Given: node that throws
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("error")
                .startNode("start")
                .finishNode("finish")
                .node("bomb", (ctx, input) -> {
                    throw new RuntimeException("Boom!");
                })
                .edge("start").to("bomb")
                .edge("bomb").to("finish")
                .build();

        // When
        GraphResult<String> result = strategy.execute("test");

        // Then
        assertThat(result.status()).isEqualTo(GraphResult.GraphStatus.ERROR);
        assertThat(result.error()).isInstanceOf(RuntimeException.class);
        assertThat(result.error().getMessage()).isEqualTo("Boom!");
    }

    @Test
    void transformer_convertsOutputBetweenNodes() {
        // Given: string node -> transform to int -> int node -> finish
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("transform")
                .startNode("start")
                .finishNode("finish")
                .node("parser", (ctx, input) -> input)
                .node("multiplier", (GraphContext ctx, Integer input) -> "Result: " + (input * 2))
                .edge("start").to("parser")
                .edge("parser").to("multiplier").transform((String s) -> Integer.parseInt(s))
                .edge("multiplier").to("finish")
                .build();

        // When
        GraphResult<String> result = strategy.execute("21");

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("Result: 42");
    }

    @Test
    void context_sharedAcrossNodes() {
        // Given: nodes that read/write context
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("shared-context")
                .startNode("start")
                .finishNode("finish")
                .node("writer", (ctx, input) -> {
                    ctx.put("written", "hello");
                    return input;
                })
                .node("reader", (ctx, input) -> {
                    String written = ctx.getOrDefault("written", String.class, "none");
                    return input + ":" + written;
                })
                .edge("start").to("writer")
                .edge("writer").to("reader")
                .edge("reader").to("finish")
                .build();

        // When
        GraphResult<String> result = strategy.execute("test");

        // Then
        assertThat(result.output()).isEqualTo("test:hello");
    }

    @Test
    void builderValidation_requiresStartNode() {
        assertThatThrownBy(() ->
                GraphCompositionStrategy.<String, String>builder("test")
                        .finishNode("finish")
                        .build()
        ).isInstanceOf(GraphExecutionException.InvalidGraphException.class)
                .hasMessageContaining("Start node");
    }

    @Test
    void builderValidation_requiresFinishNode() {
        assertThatThrownBy(() ->
                GraphCompositionStrategy.<String, String>builder("test")
                        .startNode("start")
                        .build()
        ).isInstanceOf(GraphExecutionException.InvalidGraphException.class)
                .hasMessageContaining("Finish node");
    }

    @Test
    void builderValidation_requiresEdgeTargetExists() {
        assertThatThrownBy(() ->
                GraphCompositionStrategy.<String, String>builder("test")
                        .startNode("start")
                        .finishNode("finish")
                        .edge("start").to("nonexistent")
                        .build()
        ).isInstanceOf(GraphExecutionException.InvalidGraphException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void builderValidation_preventsDuplicateNodes() {
        assertThatThrownBy(() ->
                GraphCompositionStrategy.<String, String>builder("test")
                        .node("dup", (ctx, input) -> input)
                        .node("dup", (ctx, input) -> input)
        ).isInstanceOf(GraphExecutionException.InvalidGraphException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void strategy_hasName() {
        // Given
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("my-strategy")
                .startNode("start")
                .finishNode("finish")
                .edge("start").to("finish")
                .build();

        // Then
        assertThat(strategy.name()).isEqualTo("my-strategy");
    }

    @Test
    void strategy_hasMaxIterations() {
        // Given
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("test")
                .startNode("start")
                .finishNode("finish")
                .edge("start").to("finish").and()
                .maxIterations(50)
                .build();

        // Then
        assertThat(strategy.maxIterations()).isEqualTo(50);
    }

    @Test
    void edgeBuilder_fluentChaining() {
        // Given: using fluent edge builder with method chaining
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("fluent")
                .startNode("start")
                .finishNode("finish")
                .node("a", (ctx, input) -> input)
                .node("b", (ctx, input) -> input)
                .edge("start").to("a").and()
                .edge("a").to("b").and()
                .edge("b").to("finish")
                .build();

        // When
        GraphResult<String> result = strategy.execute("test");

        // Then
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void firstMatchingEdge_wins() {
        // Given: multiple edges, first match should win
        GraphCompositionStrategy<Integer, String> strategy = GraphCompositionStrategy.<Integer, String>builder("first-match")
                .startNode("start")
                .finishNode("finish")
                .node("check", (ctx, input) -> input)
                .node("high", (GraphContext ctx, Integer input) -> "HIGH")
                .node("low", (GraphContext ctx, Integer input) -> "LOW")
                .edge("start").to("check")
                .edge("check").to("high").when((Integer n) -> n > 5)
                .edge("check").to("low").when((Integer n) -> true) // Fallback
                .edge("high").to("finish")
                .edge("low").to("finish")
                .build();

        // When
        GraphResult<String> highResult = strategy.execute(10);
        GraphResult<String> lowResult = strategy.execute(3);

        // Then
        assertThat(highResult.output()).isEqualTo("HIGH");
        assertThat(lowResult.output()).isEqualTo("LOW");
    }

    @Test
    void toString_containsRelevantInfo() {
        // Given
        GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("my-graph")
                .startNode("start")
                .finishNode("finish")
                .node("middle", (ctx, input) -> input)
                .edge("start").to("middle")
                .edge("middle").to("finish")
                .build();

        // Then
        assertThat(strategy.toString())
                .contains("my-graph")
                .contains("start")
                .contains("finish");
    }
}
