package io.github.markpollack.workflow.core;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable registry of named {@link AgentHandler} instances.
 * <p>
 * Plain Java — no Spring dependency, no auto-discovery. Callers construct
 * the registry manually (or a future auto-configuration creates it from
 * {@code @Agent}-annotated beans).
 *
 * <pre>{@code
 * AgentRegistry registry = new AgentRegistry(Map.of(
 *     "code-review", codeReviewAgent,
 *     "security-audit", securityAuditAgent
 * ));
 *
 * AgentHandler<?, ?> agent = registry.get("code-review").orElseThrow();
 * }</pre>
 */
public final class AgentRegistry {

    private final Map<String, AgentHandler<?, ?>> agents;

    /**
     * Creates a registry from the given name-to-handler map.
     * <p>
     * The map is defensively copied — subsequent changes to the source map
     * do not affect this registry. Callers are responsible for duplicate
     * detection with clear error messages.
     *
     * @param agents the name-to-handler map
     * @throws NullPointerException if agents is null or contains null keys/values
     */
    public AgentRegistry(Map<String, AgentHandler<?, ?>> agents) {
        Objects.requireNonNull(agents, "agents map must not be null");
        this.agents = Map.copyOf(agents);
    }

    /**
     * Returns the handler registered under the given name, if present.
     *
     * @param name the agent name
     * @return an Optional containing the handler, or empty if not registered
     */
    public Optional<AgentHandler<?, ?>> get(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    /**
     * Returns the set of all registered agent names.
     *
     * @return unmodifiable set of names
     */
    public Set<String> names() {
        return agents.keySet();
    }

    /**
     * Returns the number of registered agents.
     *
     * @return the registry size
     */
    public int size() {
        return agents.size();
    }

    @Override
    public String toString() {
        return "AgentRegistry[" + agents.keySet() + "]";
    }
}
