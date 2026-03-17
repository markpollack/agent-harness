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

/**
 * Thrown by {@code Steps.terminate()} to signal intentional workflow termination.
 * <p>
 * The {@code WorkflowExecutor} catches this and converts it to a terminal result
 * with the specified {@link WorkflowStatus}.
 */
public class WorkflowTerminatedException extends RuntimeException {

    private final WorkflowStatus status;

    public WorkflowTerminatedException(WorkflowStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * Returns the terminal status.
     *
     * @return the workflow status
     */
    public WorkflowStatus status() {
        return status;
    }
}
