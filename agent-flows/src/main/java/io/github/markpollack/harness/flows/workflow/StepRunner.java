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

import io.github.markpollack.harness.flows.AgentContext;
import io.github.markpollack.harness.flows.Step;

/**
 * The substrate swap seam for workflow step execution.
 * <p>
 * The same {@link Workflow} definition runs on different substrates
 * by swapping the {@code StepRunner} — a single {@code @Bean} change:
 * <ul>
 *   <li>{@link LocalStepRunner} — direct in-process call, zero overhead (default)</li>
 *   <li>{@code CheckpointingStepRunner} — JDBC crash recovery (workflow-batch module)</li>
 *   <li>{@code TemporalStepRunner} — Temporal durable execution (workflow-temporal module)</li>
 * </ul>
 * <p>
 * Implementations control retry policy, timeout, heartbeat, and crash recovery.
 * Steps must NOT retry internally when used with a durable handler — the handler
 * owns the retry contract.
 */
public interface StepRunner {

    /**
     * Executes a single step within the workflow.
     *
     * @param step  the step to execute
     * @param ctx   the execution context
     * @param input the step input
     * @param <I>   input type
     * @param <O>   output type
     * @return the step output
     */
    <I, O> O execute(Step<I, O> step, AgentContext ctx, I input);
}
