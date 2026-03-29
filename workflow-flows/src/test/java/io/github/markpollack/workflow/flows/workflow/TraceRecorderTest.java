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

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecorderTest {

    // -------------------------------------------------------------------------
    // noop
    // -------------------------------------------------------------------------

    @Test
    void noopShouldReturnEmptyTrace() {
        TraceRecorder recorder = TraceRecorder.noop();
        assertThat(recorder.getTrace("any-id")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // inMemory
    // -------------------------------------------------------------------------

    @Test
    void inMemoryShouldRecordAndRetrieveTransitions() {
        TraceRecorder recorder = TraceRecorder.inMemory();

        // Execute a 3-step workflow with tracing
        WorkflowExecutor executor = new WorkflowExecutor(recorder);
        AgentContext ctx = AgentContext.withRunId("test-run");

        Step<String, String> a = Step.named("a", (c, in) -> in + "-A");
        Step<String, String> b = Step.named("b", (c, in) -> in + "-B");
        Step<String, String> c = Step.named("c", (c2, in) -> in + "-C");

        WorkflowGraph<String, String> graph = Workflow.<String, String>define("trace-test")
                .step(a)
                .then(b)
                .then(c)
                .compile();

        String result = executor.execute(graph, ctx, "start");
        assertThat(result).isEqualTo("start-A-B-C");

        // Verify trace
        List<StepTransition> trace = recorder.getTrace("test-run");
        assertThat(trace).hasSize(3);

        // First transition: fromStep is null (first step)
        assertThat(trace.get(0).fromStep()).isNull();
        assertThat(trace.get(0).toStep()).startsWith("a-");
        assertThat(trace.get(0).workflowName()).isEqualTo("trace-test");

        // Second transition
        assertThat(trace.get(1).fromStep()).startsWith("a-");
        assertThat(trace.get(1).toStep()).startsWith("b-");

        // Third transition
        assertThat(trace.get(2).fromStep()).startsWith("b-");
        assertThat(trace.get(2).toStep()).startsWith("c-");
    }

    @Test
    void inMemoryShouldTrackDurationForEachStep() {
        TraceRecorder recorder = TraceRecorder.inMemory();
        WorkflowExecutor executor = new WorkflowExecutor(recorder);
        AgentContext ctx = AgentContext.withRunId("duration-test");

        WorkflowGraph<String, String> graph = Workflow.<String, String>define("dur-test")
                .step(Step.named("slow", (c, in) -> {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    return in;
                }))
                .compile();

        executor.execute(graph, ctx, "x");

        List<StepTransition> trace = recorder.getTrace("duration-test");
        assertThat(trace).hasSize(1);
        assertThat(trace.get(0).stepDuration().toMillis()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void inMemoryShouldSeparateTracesByRunId() {
        TraceRecorder recorder = TraceRecorder.inMemory();
        WorkflowExecutor executor = new WorkflowExecutor(recorder);

        WorkflowGraph<String, String> graph = Workflow.<String, String>define("multi")
                .step(Step.named("x", (c, in) -> in))
                .compile();

        executor.execute(graph, AgentContext.withRunId("run-1"), "a");
        executor.execute(graph, AgentContext.withRunId("run-2"), "b");

        assertThat(recorder.getTrace("run-1")).hasSize(1);
        assertThat(recorder.getTrace("run-2")).hasSize(1);
        assertThat(recorder.getTrace("run-3")).isEmpty();
    }

    @Test
    void inMemoryShouldReturnImmutableCopy() {
        TraceRecorder recorder = TraceRecorder.inMemory();
        WorkflowExecutor executor = new WorkflowExecutor(recorder);

        WorkflowGraph<String, String> graph = Workflow.<String, String>define("immutable")
                .step(Step.named("x", (c, in) -> in))
                .compile();

        executor.execute(graph, AgentContext.withRunId("test"), "input");

        List<StepTransition> trace = recorder.getTrace("test");
        assertThat(trace).isUnmodifiable();
    }

    @Test
    void tokenCountsShouldBeZeroForDeterministicSteps() {
        TraceRecorder recorder = TraceRecorder.inMemory();
        WorkflowExecutor executor = new WorkflowExecutor(recorder);

        WorkflowGraph<String, String> graph = Workflow.<String, String>define("det")
                .step(Step.named("pure", (c, in) -> in))
                .compile();

        executor.execute(graph, AgentContext.withRunId("det-run"), "x");

        List<StepTransition> trace = recorder.getTrace("det-run");
        assertThat(trace.get(0).tokensUsed()).isEqualTo(0L);
        assertThat(trace.get(0).costUsd()).isEqualTo(0.0);
    }
}
