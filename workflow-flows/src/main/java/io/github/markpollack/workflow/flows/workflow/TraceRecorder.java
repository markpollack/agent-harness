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
package io.github.markpollack.workflow.flows.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every step transition in a workflow execution.
 * <p>
 * The foundation for {@code RunDiagnosis}, Markov analysis, and replay.
 * {@link #noop()} is the default — zero allocation, zero overhead when
 * tracing is not needed. {@link #inMemory()} stores transitions in memory
 * for testing and analysis.
 */
public interface TraceRecorder {

    /**
     * Records a single step completion.
     *
     * @param transition the step transition to record
     */
    void record(StepTransition transition);

    /**
     * Retrieves the full trace for a given workflow run.
     *
     * @param workflowRunId the workflow run identifier
     * @return ordered list of transitions, or empty if not found
     */
    List<StepTransition> getTrace(String workflowRunId);

    /**
     * Returns a no-op recorder that discards all transitions.
     * Zero overhead — the default when tracing is disabled.
     *
     * @return a no-op TraceRecorder
     */
    static TraceRecorder noop() {
        return NoopTraceRecorder.INSTANCE;
    }

    /**
     * Returns an in-memory recorder that stores transitions in a thread-safe list.
     * Suitable for testing and analysis.
     *
     * @return an in-memory TraceRecorder
     */
    static TraceRecorder inMemory() {
        return new InMemoryTraceRecorder();
    }
}

/**
 * No-op implementation — zero allocation, zero overhead.
 */
final class NoopTraceRecorder implements TraceRecorder {

    static final NoopTraceRecorder INSTANCE = new NoopTraceRecorder();

    @Override
    public void record(StepTransition transition) {
        // intentionally empty
    }

    @Override
    public List<StepTransition> getTrace(String workflowRunId) {
        return List.of();
    }
}

/**
 * In-memory implementation backed by concurrent collections.
 */
final class InMemoryTraceRecorder implements TraceRecorder {

    private final Map<String, List<StepTransition>> traces = new ConcurrentHashMap<>();

    @Override
    public void record(StepTransition transition) {
        traces.computeIfAbsent(transition.workflowRunId(), k -> new CopyOnWriteArrayList<>())
                .add(transition);
    }

    @Override
    public List<StepTransition> getTrace(String workflowRunId) {
        List<StepTransition> trace = traces.get(workflowRunId);
        return trace != null ? List.copyOf(trace) : List.of();
    }
}
