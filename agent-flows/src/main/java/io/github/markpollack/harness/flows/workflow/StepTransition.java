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
package io.github.markpollack.harness.flows.workflow;

import io.github.markpollack.harness.patterns.graph.NodeType;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The atomic unit of the workflow trajectory trace.
 * <p>
 * Recorded by the {@link WorkflowExecutor} after each step completes.
 * The foundation for {@code RunDiagnosis}, Markov analysis, and replay.
 *
 * @param workflowRunId unique identifier for this workflow execution
 * @param workflowName  the workflow name
 * @param fromStep      the previous step name (null for the first step)
 * @param toStep        the step that just completed
 * @param timestamp     when this transition was recorded
 * @param stepDuration  wall-clock time the step took
 * @param tokensUsed    tokens consumed (0 for deterministic steps)
 * @param costUsd       estimated USD cost (0.0 for deterministic steps)
 * @param nodeType      whether the step was deterministic or agent-driven
 */
public record StepTransition(
        String workflowRunId,
        String workflowName,
        String fromStep,
        String toStep,
        Instant timestamp,
        Duration stepDuration,
        long tokensUsed,
        double costUsd,
        NodeType nodeType
) {

    public StepTransition {
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(workflowName, "workflowName must not be null");
        Objects.requireNonNull(toStep, "toStep must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(stepDuration, "stepDuration must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
    }
}
