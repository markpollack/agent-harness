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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Execution context for graph strategy execution.
 * <p>
 * Provides shared state and metadata that nodes can read and write during execution.
 * The context is created when graph execution starts and is passed to each node.
 * <p>
 * Design:
 * <ul>
 *   <li>Mutable state for cross-node communication</li>
 *   <li>Run identifier for tracing and logging</li>
 *   <li>Typed get/put methods for state management</li>
 * </ul>
 */
public final class GraphContext {

    private final String runId;
    private final Instant startedAt;
    private final Map<String, Object> state;
    private final String strategyName;

    /**
     * Creates a new graph execution context.
     *
     * @param strategyName the name of the graph strategy
     */
    public GraphContext(String strategyName) {
        this.runId = UUID.randomUUID().toString();
        this.startedAt = Instant.now();
        this.state = new ConcurrentHashMap<>();
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName must not be null");
    }

    /**
     * Creates a context with a specific run ID (for testing or resumption).
     *
     * @param strategyName the name of the graph strategy
     * @param runId the run identifier
     */
    public GraphContext(String strategyName, String runId) {
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.startedAt = Instant.now();
        this.state = new ConcurrentHashMap<>();
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName must not be null");
    }

    /**
     * Returns the unique run identifier for this execution.
     *
     * @return the run ID
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns when this execution started.
     *
     * @return the start instant
     */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * Returns the name of the graph strategy being executed.
     *
     * @return the strategy name
     */
    public String strategyName() {
        return strategyName;
    }

    /**
     * Gets a value from the shared state.
     *
     * @param key the state key
     * @param type the expected type
     * @param <T> the value type
     * @return the value if present and of correct type
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = state.get(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    /**
     * Gets a value from the shared state, returning a default if not present.
     *
     * @param key the state key
     * @param type the expected type
     * @param defaultValue the default value
     * @param <T> the value type
     * @return the value if present and of correct type, otherwise the default
     */
    public <T> T getOrDefault(String key, Class<T> type, T defaultValue) {
        return get(key, type).orElse(defaultValue);
    }

    /**
     * Puts a value into the shared state.
     *
     * @param key the state key
     * @param value the value to store
     */
    public void put(String key, Object value) {
        state.put(key, value);
    }

    /**
     * Removes a value from the shared state.
     *
     * @param key the state key
     * @return the removed value, or null if not present
     */
    public Object remove(String key) {
        return state.remove(key);
    }

    /**
     * Returns true if the shared state contains the given key.
     *
     * @param key the state key
     * @return true if present
     */
    public boolean containsKey(String key) {
        return state.containsKey(key);
    }

    @Override
    public String toString() {
        return "GraphContext[runId=" + runId + ", strategy=" + strategyName + "]";
    }
}
