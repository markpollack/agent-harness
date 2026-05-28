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
package io.github.markpollack.workflow.flows.steps;

import io.github.markpollack.workflow.core.AgentContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentClientStepTest {

    private final AgentContext ctx = AgentContext.create();

    @Test
    void ofShouldSubstituteInputInPromptTemplate() {
        AgentClient mockClient = (prompt, c) -> "received: " + prompt;
        AgentClientStep step = AgentClientStep.of(mockClient, "analyze: {input}");

        assertThat(step.execute(ctx, "my data")).isEqualTo("received: analyze: my data");
    }

    @Test
    void ofShouldPassContextToClient() {
        AgentContext namedCtx = AgentContext.withRunId("run-test");
        AgentClient captureCtxClient = (prompt, c) -> c.runId();
        AgentClientStep step = AgentClientStep.of(captureCtxClient, "prompt: {input}");

        assertThat(step.execute(namedCtx, "x")).isEqualTo("run-test");
    }

    @Test
    void ofShouldHandleNullInputGracefully() {
        AgentClient echo = (prompt, c) -> prompt;
        AgentClientStep step = AgentClientStep.of(echo, "process: {input}");

        assertThat(step.execute(ctx, null)).isEqualTo("process: ");
    }

    @Test
    void ofShouldLeaveTemplateUnchangedWhenNoPlaceholder() {
        AgentClient echo = (prompt, c) -> prompt;
        AgentClientStep step = AgentClientStep.of(echo, "static prompt");

        assertThat(step.execute(ctx, "ignored")).isEqualTo("static prompt");
    }

    @Test
    void executeForResultShouldPropagateTracePathToContext() {
        AgentClient traceClient = new AgentClient() {
            @Override
            public String execute(String prompt, AgentContext c) {
                return "text";
            }

            @Override
            public ExecutionResult executeForResult(String prompt, AgentContext c) {
                return new ExecutionResult("analyzed", "/tmp/traces/step-001.jsonl");
            }
        };
        AgentClientStep step = AgentClientStep.of(traceClient, "analyze: {input}");

        String result = step.execute(ctx, "data");
        assertThat(result).isEqualTo("analyzed");

        AgentContext updated = step.updateContext(ctx, result);
        assertThat(updated.get(AgentContext.TRACE_PATH))
                .hasValue("/tmp/traces/step-001.jsonl");
    }

    @Test
    void lambdaClientShouldNotSetTracePath() {
        AgentClient lambda = (prompt, c) -> "response";
        AgentClientStep step = AgentClientStep.of(lambda, "{input}");

        String result = step.execute(ctx, "hello");
        AgentContext updated = step.updateContext(ctx, result);

        assertThat(updated.get(AgentContext.TRACE_PATH)).isEmpty();
    }
}
