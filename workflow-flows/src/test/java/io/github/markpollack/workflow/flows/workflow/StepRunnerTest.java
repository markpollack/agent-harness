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

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepRunnerTest {

    // -------------------------------------------------------------------------
    // LocalStepRunner
    // -------------------------------------------------------------------------

    @Test
    void localHandlerShouldExecuteStepDirectly() {
        LocalStepRunner handler = new LocalStepRunner();
        Step<String, String> step = Step.named("upper", (ctx, in) -> in.toUpperCase());

        String result = handler.execute(step, AgentContext.create(), "hello");

        assertThat(result).isEqualTo("HELLO");
    }

    @Test
    void localHandlerShouldPropagateExceptions() {
        LocalStepRunner handler = new LocalStepRunner();
        Step<String, String> step = Step.named("fail", (ctx, in) -> {
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(() -> handler.execute(step, AgentContext.create(), "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    // -------------------------------------------------------------------------
    // WorkflowExecutor with custom StepRunner
    // -------------------------------------------------------------------------

    @Test
    void executorShouldDispatchAllStepsThroughHandler() {
        List<String> dispatched = new ArrayList<>();
        StepRunner spy = new StepRunner() {
            @Override
            @SuppressWarnings("unchecked")
            public <I, O> O execute(Step<I, O> step, AgentContext ctx, I input) {
                dispatched.add(step.name());
                return step.execute(ctx, input);
            }
        };

        WorkflowExecutor executor = new WorkflowExecutor(spy, TraceRecorder.noop());
        WorkflowGraph<String, String> graph = Workflow.<String, String>define("spy-test")
                .step(Step.named("a", (ctx, in) -> in + "A"))
                .then(Step.named("b", (ctx, in) -> in + "B"))
                .compile();

        String result = executor.execute(graph, AgentContext.create(), "x");

        assertThat(result).isEqualTo("xAB");
        // Both steps dispatched through handler
        assertThat(dispatched).hasSize(2);
        assertThat(dispatched.get(0)).isEqualTo("a");
        assertThat(dispatched.get(1)).isEqualTo("b");
    }

    @Test
    void executorShouldPropagateHandlerExceptions() {
        StepRunner failing = new StepRunner() {
            @Override
            public <I, O> O execute(Step<I, O> step, AgentContext ctx, I input) {
                throw new RuntimeException("handler failed");
            }
        };

        WorkflowExecutor executor = new WorkflowExecutor(failing, TraceRecorder.noop());
        WorkflowGraph<String, String> graph = Workflow.<String, String>define("fail-test")
                .step(Step.named("x", (ctx, in) -> in))
                .compile();

        assertThatThrownBy(() -> executor.execute(graph, AgentContext.create(), "input"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("handler failed");
    }
}
