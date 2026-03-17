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

import io.github.markpollack.harness.flows.Step;
import io.github.markpollack.harness.patterns.graph.NodeType;

import java.util.Objects;

/**
 * A node in the {@link WorkflowGraph} intermediate representation.
 * <p>
 * Each node wraps a {@link Step} and declares its {@link NodeType}
 * ({@code DETERMINISTIC} or {@code AGENT}) for cost tracking and visualization.
 *
 * @param name the unique node name within the graph
 * @param type whether this node is deterministic or LLM-driven
 * @param step the step implementation to execute
 */
public record WorkflowNode(String name, NodeType type, Step<?, ?> step) {

    public WorkflowNode {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(step, "step must not be null");
    }

    /**
     * Creates a deterministic node.
     *
     * @param name the node name
     * @param step the step implementation
     * @return a new WorkflowNode with DETERMINISTIC type
     */
    public static WorkflowNode deterministic(String name, Step<?, ?> step) {
        return new WorkflowNode(name, NodeType.DETERMINISTIC, step);
    }

    /**
     * Creates an agent (LLM-driven) node.
     *
     * @param name the node name
     * @param step the step implementation
     * @return a new WorkflowNode with AGENT type
     */
    public static WorkflowNode agent(String name, Step<?, ?> step) {
        return new WorkflowNode(name, NodeType.AGENT, step);
    }
}
