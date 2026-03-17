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
import io.github.markpollack.harness.flows.Step;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepTest {

    private final AgentContext ctx = AgentContext.create();

    // -------------------------------------------------------------------------
    // Steps.of() factory (renamed from Step.of())
    // -------------------------------------------------------------------------

    @Test
    void ofBiFunctionShouldWrapFunctionAsStep() {
        Step<String, Integer> step = Steps.of((AgentContext c, String input) -> input.length());

        assertThat(step.execute(ctx, "hello")).isEqualTo(5);
    }

    @Test
    void ofBiFunctionShouldReceiveContext() {
        AgentContext namedCtx = AgentContext.withRunId("run-99");
        Step<String, String> step = Steps.of((AgentContext c, String input) -> c.runId() + ":" + input);

        assertThat(step.execute(namedCtx, "data")).isEqualTo("run-99:data");
    }

    @Test
    void ofFunctionShouldWrapFunctionIgnoringContext() {
        Step<String, String> step = Steps.of((String s) -> s.toUpperCase());

        assertThat(step.execute(ctx, "hello")).isEqualTo("HELLO");
    }

    @Test
    void ofFunctionShouldHandleNullableInput() {
        Step<String, Integer> step = Steps.of((String s) -> s == null ? -1 : s.length());

        assertThat(step.execute(ctx, null)).isEqualTo(-1);
        assertThat(step.execute(ctx, "abc")).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Step.named() factory
    // -------------------------------------------------------------------------

    @Test
    void namedShouldReturnStepWithGivenName() {
        Step<String, String> step = Step.named("fetch-diff", (ctx, input) -> input.toUpperCase());

        assertThat(step.name()).isEqualTo("fetch-diff");
        assertThat(step.execute(ctx, "hello")).isEqualTo("HELLO");
    }

    @Test
    void namedStepShouldHaveDescriptiveToString() {
        Step<String, String> step = Step.named("analyze", (ctx, input) -> input);

        assertThat(step.toString()).contains("analyze");
    }

    // -------------------------------------------------------------------------
    // Step.noop()
    // -------------------------------------------------------------------------

    @Test
    void noopShouldPassInputThrough() {
        Step<String, String> noop = Step.noop();

        assertThat(noop.execute(ctx, "hello")).isEqualTo("hello");
    }

    @Test
    void noopShouldReturnNoopAsName() {
        Step<String, String> noop = Step.noop();

        assertThat(noop.name()).isEqualTo("noop");
    }

    @Test
    void noopShouldHandleNullInput() {
        Step<String, String> noop = Step.noop();

        assertThat(noop.execute(ctx, null)).isNull();
    }

    @Test
    void noopShouldWorkWithDifferentTypes() {
        Step<Integer, Integer> noop = Step.noop();

        assertThat(noop.execute(ctx, 42)).isEqualTo(42);
    }

    // -------------------------------------------------------------------------
    // Step.name() default implementation
    // -------------------------------------------------------------------------

    @Test
    void claudeStepShouldReturnClassName() {
        ClaudeStep step = ClaudeStep.of("test: {input}");

        assertThat(step.name()).isEqualTo("ClaudeStep");
    }
}
