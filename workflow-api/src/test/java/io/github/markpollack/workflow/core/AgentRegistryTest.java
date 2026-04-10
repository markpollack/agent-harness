package io.github.markpollack.workflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentRegistry")
class AgentRegistryTest {

    private final AgentHandler<String, String> echoAgent = (ctx, input) -> input;
    private final AgentHandler<String, String> upperAgent = (ctx, input) -> input.toUpperCase();

    @Test
    void shouldLookupAgentByName() {
        AgentRegistry registry = new AgentRegistry(Map.of(
                "echo", echoAgent,
                "upper", upperAgent
        ));

        assertThat(registry.get("echo")).contains(echoAgent);
        assertThat(registry.get("upper")).contains(upperAgent);
    }

    @Test
    void shouldReturnEmptyForUnknownAgent() {
        AgentRegistry registry = new AgentRegistry(Map.of("echo", echoAgent));

        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void shouldReturnAllRegisteredNames() {
        AgentRegistry registry = new AgentRegistry(Map.of(
                "echo", echoAgent,
                "upper", upperAgent
        ));

        assertThat(registry.names()).containsExactlyInAnyOrder("echo", "upper");
    }

    @Test
    void shouldReturnCorrectSize() {
        AgentRegistry registry = new AgentRegistry(Map.of(
                "echo", echoAgent,
                "upper", upperAgent
        ));

        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void shouldBeImmutable() {
        Map<String, AgentHandler<?, ?>> mutableMap = new HashMap<>();
        mutableMap.put("echo", echoAgent);

        AgentRegistry registry = new AgentRegistry(mutableMap);

        // Mutate original map — registry should not be affected
        mutableMap.put("extra", upperAgent);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("extra")).isEmpty();
    }

    @Test
    void shouldRejectNullMap() {
        assertThatThrownBy(() -> new AgentRegistry(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportEmptyRegistry() {
        AgentRegistry registry = new AgentRegistry(Map.of());

        assertThat(registry.size()).isZero();
        assertThat(registry.names()).isEmpty();
        assertThat(registry.get("anything")).isEmpty();
    }
}
