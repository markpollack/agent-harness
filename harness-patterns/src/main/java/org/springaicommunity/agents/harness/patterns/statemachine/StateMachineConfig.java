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

import org.springaicommunity.agents.judge.jury.Jury;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration for Status-Based State Machine loop pattern.
 * <p>
 * This pattern is used by OpenHands, Embabel, and AgentLite.
 * The loop executes state transitions until reaching a terminal state.
 *
 * <p>Key features:
 * <ul>
 *   <li>Explicit state definitions with valid transitions</li>
 *   <li>State-specific actions and handlers</li>
 *   <li>Transition guards and conditions</li>
 *   <li>Terminal states that end the loop</li>
 * </ul>
 *
 * <p>Termination conditions:
 * <ul>
 *   <li>Terminal state reached (COMPLETED, FAILED, CANCELLED)</li>
 *   <li>Max iterations reached</li>
 *   <li>Timeout exceeded</li>
 *   <li>Abort signal received</li>
 * </ul>
 *
 * @param states all defined states in the machine
 * @param initialState starting state name
 * @param maxIterations maximum number of state transitions
 * @param timeout maximum duration for entire execution
 * @param workingDirectory workspace directory for file operations
 * @param jury optional spring-ai-agents Jury for state evaluation
 * @param evaluateOnStates evaluate with jury when entering these states
 * @param tools list of available tool names
 * @param finishToolName name of the finish tool
 * @param stateHandlers custom handlers for specific states
 */
public record StateMachineConfig(
        Map<String, AgentState> states,
        String initialState,
        int maxIterations,
        Duration timeout,
        Path workingDirectory,
        Optional<Jury> jury,
        List<String> evaluateOnStates,
        List<String> tools,
        String finishToolName,
        Map<String, StateHandler> stateHandlers
) {
    /**
     * Handler for state entry/execution.
     */
    @FunctionalInterface
    public interface StateHandler {
        /**
         * Handles entry into a state.
         *
         * @param context the state context
         * @return the transition result (next state or stay)
         */
        TransitionResult handle(StateContext context);
    }

    /**
     * Context provided to state handlers.
     */
    public record StateContext(
            AgentState currentState,
            Object input,
            Object lastOutput,
            int iterationCount,
            Map<String, Object> attributes
    ) {
        public StateContext withAttribute(String key, Object value) {
            var newAttrs = new java.util.HashMap<>(attributes);
            newAttrs.put(key, value);
            return new StateContext(currentState, input, lastOutput, iterationCount, Map.copyOf(newAttrs));
        }
    }

    /**
     * Result of a state transition.
     */
    public record TransitionResult(
            String nextState,
            Object output,
            boolean shouldContinue,
            String reason
    ) {
        public static TransitionResult stay(Object output) {
            return new TransitionResult(null, output, true, null);
        }

        public static TransitionResult transitionTo(String state, Object output) {
            return new TransitionResult(state, output, true, null);
        }

        public static TransitionResult complete(Object output, String reason) {
            return new TransitionResult("COMPLETED", output, false, reason);
        }

        public static TransitionResult fail(Object output, String reason) {
            return new TransitionResult("FAILED", output, false, reason);
        }
    }

    public StateMachineConfig {
        if (states == null || states.isEmpty()) {
            states = Map.of(
                    AgentState.INITIAL.name(), AgentState.INITIAL,
                    AgentState.RUNNING.name(), AgentState.RUNNING,
                    AgentState.AWAITING_JUDGMENT.name(), AgentState.AWAITING_JUDGMENT,
                    AgentState.COMPLETED.name(), AgentState.COMPLETED,
                    AgentState.FAILED.name(), AgentState.FAILED,
                    AgentState.CANCELLED.name(), AgentState.CANCELLED
            );
        }
        if (initialState == null || initialState.isBlank()) {
            initialState = "INITIAL";
        }
        if (!states.containsKey(initialState)) {
            throw new IllegalArgumentException("Initial state not found in states: " + initialState);
        }
        if (maxIterations <= 0) {
            maxIterations = 100;
        }
        if (timeout == null) {
            timeout = Duration.ofMinutes(60);
        }
        if (jury == null) {
            jury = Optional.empty();
        }
        if (evaluateOnStates == null) {
            evaluateOnStates = List.of("AWAITING_JUDGMENT");
        }
        if (tools == null) {
            tools = List.of();
        }
        if (finishToolName == null || finishToolName.isBlank()) {
            finishToolName = "complete_task";
        }
        if (stateHandlers == null) {
            stateHandlers = Map.of();
        }
    }

    /**
     * Gets a state by name.
     */
    public AgentState getState(String name) {
        return states.get(name);
    }

    /**
     * Returns true if the state is terminal.
     */
    public boolean isTerminal(String stateName) {
        AgentState state = states.get(stateName);
        return state != null && state.terminal();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Map<String, AgentState> states = Map.of();
        private String initialState = "INITIAL";
        private int maxIterations = 100;
        private Duration timeout = Duration.ofMinutes(60);
        private Path workingDirectory;
        private Optional<Jury> jury = Optional.empty();
        private List<String> evaluateOnStates = List.of("AWAITING_JUDGMENT");
        private List<String> tools = List.of();
        private String finishToolName = "complete_task";
        private Map<String, StateHandler> stateHandlers = Map.of();

        public Builder states(Map<String, AgentState> states) {
            this.states = states;
            return this;
        }

        public Builder addState(AgentState state) {
            var newStates = new java.util.HashMap<>(states);
            newStates.put(state.name(), state);
            this.states = Map.copyOf(newStates);
            return this;
        }

        public Builder initialState(String initialState) {
            this.initialState = initialState;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder jury(Jury jury) {
            this.jury = Optional.ofNullable(jury);
            return this;
        }

        public Builder evaluateOnStates(List<String> evaluateOnStates) {
            this.evaluateOnStates = evaluateOnStates;
            return this;
        }

        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder finishToolName(String finishToolName) {
            this.finishToolName = finishToolName;
            return this;
        }

        public Builder stateHandlers(Map<String, StateHandler> stateHandlers) {
            this.stateHandlers = stateHandlers;
            return this;
        }

        public Builder addStateHandler(String stateName, StateHandler handler) {
            var newHandlers = new java.util.HashMap<>(stateHandlers);
            newHandlers.put(stateName, handler);
            this.stateHandlers = Map.copyOf(newHandlers);
            return this;
        }

        public StateMachineConfig build() {
            return new StateMachineConfig(
                    states, initialState, maxIterations, timeout, workingDirectory,
                    jury, evaluateOnStates, tools, finishToolName, stateHandlers
            );
        }
    }
}
