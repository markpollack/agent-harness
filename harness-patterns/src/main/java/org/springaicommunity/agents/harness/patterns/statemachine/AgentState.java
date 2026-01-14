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
package org.springaicommunity.agents.harness.patterns.statemachine;

import java.util.Set;

/**
 * Represents a state in the agent state machine.
 * <p>
 * Each state defines:
 * <ul>
 *   <li>A unique name/identifier</li>
 *   <li>Whether it's terminal (loop ends)</li>
 *   <li>Valid transitions to other states</li>
 *   <li>Entry/exit actions</li>
 * </ul>
 *
 * @param name unique state identifier
 * @param terminal if true, reaching this state terminates the loop
 * @param validTransitions set of state names this state can transition to
 * @param description human-readable description of the state
 */
public record AgentState(
        String name,
        boolean terminal,
        Set<String> validTransitions,
        String description
) {
    /**
     * Common states used in SDLC agents.
     */
    public static final AgentState INITIAL = new AgentState(
            "INITIAL",
            false,
            Set.of("RUNNING", "FAILED"),
            "Initial state before processing begins"
    );

    public static final AgentState RUNNING = new AgentState(
            "RUNNING",
            false,
            Set.of("AWAITING_FEEDBACK", "AWAITING_JUDGMENT", "COMPLETED", "FAILED", "PAUSED"),
            "Agent is actively executing"
    );

    public static final AgentState AWAITING_FEEDBACK = new AgentState(
            "AWAITING_FEEDBACK",
            false,
            Set.of("RUNNING", "COMPLETED", "FAILED"),
            "Waiting for user or system feedback"
    );

    public static final AgentState AWAITING_JUDGMENT = new AgentState(
            "AWAITING_JUDGMENT",
            false,
            Set.of("RUNNING", "COMPLETED", "FAILED"),
            "Waiting for jury evaluation"
    );

    public static final AgentState PAUSED = new AgentState(
            "PAUSED",
            false,
            Set.of("RUNNING", "COMPLETED", "FAILED"),
            "Temporarily paused"
    );

    public static final AgentState COMPLETED = new AgentState(
            "COMPLETED",
            true,
            Set.of(),
            "Successfully completed"
    );

    public static final AgentState FAILED = new AgentState(
            "FAILED",
            true,
            Set.of(),
            "Failed with error"
    );

    public static final AgentState CANCELLED = new AgentState(
            "CANCELLED",
            true,
            Set.of(),
            "Cancelled by user"
    );

    /**
     * Creates a custom state.
     */
    public static AgentState of(String name, boolean terminal, Set<String> transitions) {
        return new AgentState(name, terminal, transitions, name);
    }

    /**
     * Creates a custom state with description.
     */
    public static AgentState of(String name, boolean terminal, Set<String> transitions, String description) {
        return new AgentState(name, terminal, transitions, description);
    }

    /**
     * Returns true if this state can transition to the given state.
     */
    public boolean canTransitionTo(AgentState target) {
        return validTransitions.contains(target.name());
    }

    /**
     * Returns true if this state can transition to the given state name.
     */
    public boolean canTransitionTo(String targetName) {
        return validTransitions.contains(targetName);
    }
}
