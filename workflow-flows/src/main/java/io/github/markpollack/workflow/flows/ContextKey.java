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
package io.github.markpollack.workflow.flows;

import java.util.Objects;

/**
 * A type-safe key for {@link AgentContext} entries.
 * <p>
 * Implements Bloch's <em>Effective Java</em> Item 33 — "Typesafe Heterogeneous Container."
 * The cast is centralized inside {@link AgentContext#get(ContextKey)}; no raw casts
 * scattered across steps. Same pattern as Netty's {@code AttributeKey<T>} and
 * gRPC's {@code Context.Key<T>}.
 * <p>
 * Framework-defined well-known keys are constants on {@link AgentContext}.
 * User-defined keys are static constants on the producing step class:
 * <pre>{@code
 * static final ContextKey<ReviewReport> REVIEW_RESULT =
 *     ContextKey.of("reviewReport", ReviewReport.class);
 * }</pre>
 *
 * @param <T> the value type associated with this key
 */
public final class ContextKey<T> {

    private final String key;
    private final Class<T> type;

    private ContextKey(String key, Class<T> type) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    /**
     * Creates a new context key.
     *
     * @param key  the string identifier (unique within a context)
     * @param type the value type class
     * @param <T>  the value type
     * @return a new ContextKey
     */
    public static <T> ContextKey<T> of(String key, Class<T> type) {
        return new ContextKey<>(key, type);
    }

    /**
     * Returns the string identifier for this key.
     *
     * @return the key string
     */
    public String key() {
        return key;
    }

    /**
     * Returns the value type class for this key.
     *
     * @return the type class
     */
    public Class<T> type() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContextKey<?> that)) return false;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "ContextKey[" + key + ", " + type.getSimpleName() + "]";
    }
}
