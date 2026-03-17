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
package io.github.markpollack.harness.flows;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Execution context passed to every {@link AgentStep} in a flow run.
 * <p>
 * {@code AgentContext} carries a run identifier and a metadata map for sharing
 * state across steps. It is immutable: {@link #withMetadata(String, Object)} returns
 * a new instance rather than mutating the current one.
 * <p>
 * Create with {@link #create()} for a new run, or {@link #withRunId(String)} when
 * a specific identifier is needed (e.g., for traceability with an external job ID).
 */
public final class AgentContext {

    private final String runId;
    private final Map<String, Object> metadata;

    private AgentContext(String runId, Map<String, Object> metadata) {
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.metadata = Map.copyOf(metadata);
    }

    /**
     * Creates a new context with a random run ID and empty metadata.
     *
     * @return a new AgentContext
     */
    public static AgentContext create() {
        return new AgentContext(UUID.randomUUID().toString(), Map.of());
    }

    /**
     * Creates a new context with the given run ID and empty metadata.
     *
     * @param runId the run identifier
     * @return a new AgentContext
     */
    public static AgentContext withRunId(String runId) {
        return new AgentContext(runId, Map.of());
    }

    /**
     * Returns the run identifier for this flow execution.
     *
     * @return the run ID
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns an immutable view of the metadata map.
     *
     * @return the metadata
     */
    public Map<String, Object> metadata() {
        return metadata;
    }

    /**
     * Returns a new context with the given key-value pair added to metadata.
     *
     * @param key   the metadata key
     * @param value the metadata value
     * @return a new AgentContext with the additional entry
     */
    public AgentContext withMetadata(String key, Object value) {
        Map<String, Object> updated = new HashMap<>(metadata);
        updated.put(key, value);
        return new AgentContext(runId, updated);
    }

    @Override
    public String toString() {
        return "AgentContext[runId=" + runId + ", metadata=" + metadata + "]";
    }
}
