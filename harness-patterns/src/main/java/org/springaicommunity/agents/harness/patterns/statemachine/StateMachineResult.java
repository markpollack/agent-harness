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

import org.springaicommunity.agents.harness.core.LoopResult;
import org.springaicommunity.agents.harness.core.LoopStatus;
import org.springaicommunity.agents.harness.core.TerminationReason;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result of executing a StateMachineLoop.
 * <p>
 * Provides access to common result data via the LoopResult interface,
 * plus pattern-specific data such as state transitions and final state.
 */
public record StateMachineResult(
        // Common fields (from LoopResult)
        String runId,
        String output,
        LoopStatus status,
        TerminationReason reason,
        int turnsCompleted,
        Duration totalDuration,
        long totalTokens,
        double estimatedCost,

        // Pattern-specific fields
        List<StateMachineLoop.StateTransition> transitions,
        AgentState finalState,
        Map<String, Object> finalAttributes
) implements LoopResult {

    /**
     * Returns true if the loop reached a terminal state normally.
     */
    public boolean reachedTerminalState() {
        return finalState.terminal() && reason() == TerminationReason.STATE_TERMINAL;
    }

    /**
     * Returns true if the final state is the COMPLETED state.
     */
    public boolean completedSuccessfully() {
        return "COMPLETED".equals(finalState.name()) && isSuccess();
    }

    /**
     * Returns true if the final state is the FAILED state.
     */
    public boolean failedInStateMachine() {
        return "FAILED".equals(finalState.name());
    }

    /**
     * Returns the sequence of state names visited during execution.
     */
    public List<String> stateSequence() {
        if (transitions.isEmpty()) return List.of();
        List<String> sequence = new ArrayList<>();
        sequence.add(transitions.get(0).fromState().name());
        transitions.forEach(t -> sequence.add(t.toState().name()));
        return sequence;
    }

    /**
     * Returns true if the given state was visited during execution.
     */
    public boolean visitedState(String stateName) {
        return transitions.stream()
                .anyMatch(t -> t.fromState().name().equals(stateName) ||
                               t.toState().name().equals(stateName));
    }

    /**
     * Returns the total number of state transitions.
     */
    public int transitionCount() {
        return transitions.size();
    }

    /**
     * Returns an attribute from the final state context.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) finalAttributes.get(key);
    }

    /**
     * Creates a successful result.
     */
    public static StateMachineResult success(
            String runId,
            String output,
            int turns,
            Duration duration,
            long tokens,
            double cost,
            List<StateMachineLoop.StateTransition> transitions,
            AgentState finalState,
            Map<String, Object> attributes
    ) {
        return new StateMachineResult(
                runId,
                output,
                LoopStatus.COMPLETED,
                TerminationReason.STATE_TERMINAL,
                turns,
                duration,
                tokens,
                cost,
                transitions,
                finalState,
                attributes
        );
    }

    /**
     * Creates a result with a specific termination reason.
     */
    public static StateMachineResult terminated(
            String runId,
            String output,
            TerminationReason reason,
            LoopStatus status,
            int turns,
            Duration duration,
            long tokens,
            double cost,
            List<StateMachineLoop.StateTransition> transitions,
            AgentState finalState,
            Map<String, Object> attributes
    ) {
        return new StateMachineResult(
                runId,
                output,
                status,
                reason,
                turns,
                duration,
                tokens,
                cost,
                transitions,
                finalState,
                attributes
        );
    }

    /**
     * Creates a failed result.
     */
    public static StateMachineResult failed(
            String runId,
            int turns,
            Duration duration,
            List<StateMachineLoop.StateTransition> transitions,
            AgentState finalState
    ) {
        return new StateMachineResult(
                runId,
                null,
                LoopStatus.FAILED,
                TerminationReason.ERROR,
                turns,
                duration,
                0,
                0.0,
                transitions,
                finalState,
                Map.of()
        );
    }
}
