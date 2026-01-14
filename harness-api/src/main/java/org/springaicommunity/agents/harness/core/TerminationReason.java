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
package org.springaicommunity.agents.harness.core;

/**
 * Reason for loop termination.
 */
public enum TerminationReason {
    /** Loop has not terminated yet */
    NOT_TERMINATED,
    /** Score threshold was met */
    SCORE_THRESHOLD_MET,
    /** Maximum iterations/trials reached */
    MAX_ITERATIONS_REACHED,
    /** Maximum turns reached */
    MAX_TURNS_REACHED,
    /** Agent detected as stuck (no progress) */
    STUCK_DETECTED,
    /** User approved the result */
    USER_APPROVAL,
    /** Finish/submit tool was called */
    FINISH_TOOL_CALLED,
    /** Terminal state reached (state machine) */
    STATE_TERMINAL,
    /** Workflow completed all steps */
    WORKFLOW_COMPLETE,
    /** Timeout exceeded */
    TIMEOUT,
    /** Cost limit exceeded */
    COST_LIMIT_EXCEEDED,
    /** External abort signal received */
    EXTERNAL_SIGNAL,
    /** Error occurred during execution */
    ERROR
}
