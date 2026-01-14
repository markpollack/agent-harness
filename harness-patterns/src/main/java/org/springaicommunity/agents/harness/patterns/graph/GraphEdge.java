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

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A directed edge in a graph strategy with optional condition and transformation.
 * <p>
 * Edges connect nodes and can have conditions that determine when the edge is taken.
 * When multiple edges leave a node, they are evaluated in order and the first
 * matching edge is taken (first-match semantics).
 * <p>
 * Design:
 * <ul>
 *   <li>Edges have conditions that return true/false</li>
 *   <li>Edges can transform output before passing to next node</li>
 *   <li>First matching edge wins (order matters)</li>
 * </ul>
 *
 * @param <T> the type of output from the source node
 */
public final class GraphEdge<T> {

    private final String targetNodeName;
    private final Predicate<T> condition;
    private final Function<T, ?> transformer;

    /**
     * Package-private constructor for builder use.
     */
    GraphEdge(String targetNodeName, Predicate<T> condition, Function<T, ?> transformer) {
        this.targetNodeName = Objects.requireNonNull(targetNodeName, "targetNodeName must not be null");
        this.condition = Objects.requireNonNull(condition, "condition must not be null");
        this.transformer = Objects.requireNonNull(transformer, "transformer must not be null");
    }

    /**
     * Creates an unconditional edge to the target node.
     *
     * @param targetNodeName the name of the target node
     * @param <T> the output type
     * @return a new GraphEdge
     */
    public static <T> GraphEdge<T> to(String targetNodeName) {
        return new GraphEdge<>(targetNodeName, t -> true, Function.identity());
    }

    /**
     * Returns a new edge with the given condition.
     * <p>
     * The condition is evaluated against the output of the source node.
     * If the condition returns true, this edge is taken.
     *
     * @param condition the condition predicate
     * @return a new GraphEdge with the condition
     */
    public GraphEdge<T> when(Predicate<T> condition) {
        return new GraphEdge<>(this.targetNodeName, condition, this.transformer);
    }

    /**
     * Returns a new edge with the given transformer.
     * <p>
     * The transformer converts the output of the source node to the input
     * expected by the target node.
     *
     * @param transformer the transformation function
     * @param <U> the transformed type
     * @return a new GraphEdge with the transformer
     */
    public <U> GraphEdge<T> transform(Function<T, U> transformer) {
        return new GraphEdge<>(this.targetNodeName, this.condition, transformer);
    }

    /**
     * Returns the name of the target node.
     *
     * @return the target node name
     */
    public String targetNodeName() {
        return targetNodeName;
    }

    /**
     * Tests if this edge should be taken given the output.
     *
     * @param output the output from the source node
     * @return true if the condition passes
     */
    public boolean matches(T output) {
        return condition.test(output);
    }

    /**
     * Transforms the output before passing to the target node.
     *
     * @param output the output from the source node
     * @return the transformed output
     */
    @SuppressWarnings("unchecked")
    public Object transformOutput(T output) {
        return transformer.apply(output);
    }

    @Override
    public String toString() {
        return "GraphEdge[-> " + targetNodeName + "]";
    }
}
