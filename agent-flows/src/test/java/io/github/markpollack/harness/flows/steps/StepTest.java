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
package io.github.markpollack.harness.flows.steps;

import io.github.markpollack.harness.flows.AgentContext;
import io.github.markpollack.harness.flows.AgentStep;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepTest {

    private final AgentContext ctx = AgentContext.create();

    @Test
    void ofBiFunctionShouldWrapFunctionAsAgentStep() {
        AgentStep<String, Integer> step = Step.of((AgentContext c, String input) -> input.length());

        assertThat(step.execute(ctx, "hello")).isEqualTo(5);
    }

    @Test
    void ofBiFunctionShouldReceiveContext() {
        AgentContext namedCtx = AgentContext.withRunId("run-99");
        AgentStep<String, String> step = Step.of((AgentContext c, String input) -> c.runId() + ":" + input);

        assertThat(step.execute(namedCtx, "data")).isEqualTo("run-99:data");
    }

    @Test
    void ofFunctionShouldWrapFunctionIgnoringContext() {
        AgentStep<String, String> step = Step.of((String s) -> s.toUpperCase());

        assertThat(step.execute(ctx, "hello")).isEqualTo("HELLO");
    }

    @Test
    void ofFunctionShouldHandleNullableInput() {
        AgentStep<String, Integer> step = Step.of((String s) -> s == null ? -1 : s.length());

        assertThat(step.execute(ctx, null)).isEqualTo(-1);
        assertThat(step.execute(ctx, "abc")).isEqualTo(3);
    }
}
