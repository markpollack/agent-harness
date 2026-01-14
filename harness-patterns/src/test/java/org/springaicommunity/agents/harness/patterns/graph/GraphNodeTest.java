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

class GraphNodeTest {

    @Test
    void functionNode_executesFunction() {
        // Given
        GraphNode<String, String> node = GraphNode.of("test", (ctx, input) -> "Hello, " + input);
        GraphContext context = new GraphContext("test-graph");

        // When
        String result = node.execute(context, "World");

        // Then
        assertThat(result).isEqualTo("Hello, World");
    }

    @Test
    void functionNode_hasCorrectName() {
        // Given
        GraphNode<String, String> node = GraphNode.of("my-node", (ctx, input) -> input);

        // Then
        assertThat(node.name()).isEqualTo("my-node");
    }

    @Test
    void functionNode_canAccessContext() {
        // Given
        GraphNode<String, String> node = GraphNode.of("test", (ctx, input) -> {
            ctx.put("visited", true);
            return input;
        });
        GraphContext context = new GraphContext("test-graph");

        // When
        node.execute(context, "test");

        // Then
        assertThat(context.get("visited", Boolean.class)).contains(true);
    }

    @Test
    void functionNode_requiresName() {
        assertThatThrownBy(() -> GraphNode.of(null, (ctx, input) -> input))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void functionNode_requiresFunction() {
        assertThatThrownBy(() -> GraphNode.of("test", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("function");
    }

    @Test
    void functionNode_toString() {
        // Given
        GraphNode<String, String> node = GraphNode.of("my-node", (ctx, input) -> input);

        // Then
        assertThat(node.toString()).contains("FunctionGraphNode").contains("my-node");
    }

    @Test
    void functionNode_canTransformTypes() {
        // Given
        GraphNode<Integer, String> node = GraphNode.of("int-to-string", (ctx, input) -> "Number: " + input);
        GraphContext context = new GraphContext("test-graph");

        // When
        String result = node.execute(context, 42);

        // Then
        assertThat(result).isEqualTo("Number: 42");
    }

    @Test
    void functionNode_canReturnNull() {
        // Given
        GraphNode<String, String> node = GraphNode.of("nullable", (ctx, input) -> null);
        GraphContext context = new GraphContext("test-graph");

        // When
        String result = node.execute(context, "test");

        // Then
        assertThat(result).isNull();
    }
}
