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
package io.github.markpollack.harness.patterns.graph;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An edge declaration in a {@link Blueprint}.
 * <p>
 * Captures source node, target node, an optional condition predicate, and an
 * optional output transformer. The {@link Blueprint.BlueprintBuilder#sequential()}
 * convenience method produces unconditional {@code BlueprintEdge}s automatically.
 * <p>
 * Conditions and transformers are typed as {@code Object} internally to allow
 * heterogeneous edge declarations in the same builder chain. The underlying
 * {@link GraphEdge} handles type-unsafe traversal via the same mechanism as
 * {@link GraphCompositionStrategyBuilder}.
 *
 * @param from        the source node name
 * @param to          the target node name
 * @param condition   predicate evaluated against the source node's output;
 *                    {@code obj -> true} for unconditional edges
 * @param transformer function applied to the output before passing to the target;
 *                    {@code Function.identity()} when no conversion is needed
 */
public record BlueprintEdge(
        String from,
        String to,
        Predicate<Object> condition,
        Function<Object, Object> transformer) {

    public BlueprintEdge {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(transformer, "transformer must not be null");
    }

    /**
     * Creates an unconditional edge with no type transformation.
     *
     * @param from the source node name
     * @param to   the target node name
     * @return an unconditional BlueprintEdge
     */
    public static BlueprintEdge of(String from, String to) {
        return new BlueprintEdge(from, to, obj -> true, Function.identity());
    }

    /**
     * Creates a conditional edge with no type transformation.
     *
     * @param from      the source node name
     * @param to        the target node name
     * @param condition predicate on the source node's output
     * @param <T>       the source output type
     * @return a conditional BlueprintEdge
     */
    @SuppressWarnings("unchecked")
    public static <T> BlueprintEdge when(String from, String to, Predicate<T> condition) {
        return new BlueprintEdge(from, to, (Predicate<Object>) condition, Function.identity());
    }

    /**
     * Creates a conditional edge with a type transformation.
     *
     * @param from        the source node name
     * @param to          the target node name
     * @param condition   predicate on the source output
     * @param transformer transforms source output to target input
     * @param <T>         the source output type
     * @param <U>         the target input type
     * @return a conditional BlueprintEdge with transformation
     */
    @SuppressWarnings("unchecked")
    public static <T, U> BlueprintEdge of(String from, String to, Predicate<T> condition, Function<T, U> transformer) {
        return new BlueprintEdge(from, to, (Predicate<Object>) condition, (Function<Object, Object>) transformer);
    }
}
