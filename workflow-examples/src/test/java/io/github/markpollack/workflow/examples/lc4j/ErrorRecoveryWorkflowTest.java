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
package io.github.markpollack.workflow.examples.lc4j;

import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transliteration of LangChain4j's {@code .errorHandler()} and {@code .onError()} retry pattern.
 *
 * <h2>LC4j equivalent (collapsed)</h2>
 * <pre>
 * Workflow<String, String> pipeline = sequenceBuilder()
 *     .first(riskyStep.onError(MissingTopicException.class, recoveryStep))
 *     .build();
 * pipeline.execute(input);
 * </pre>
 *
 * <h2>Our DSL equivalent</h2>
 * <pre>
 * Workflow.define("error-recovery")
 *     .step(riskyStep)
 *         .onError(MissingTopicException.class, recoveryStep)
 *     .run(input);
 * </pre>
 *
 * {@code onError()} attaches a typed error edge to the preceding step. When the step
 * throws the specified exception, execution routes to the recovery step. Unmatched
 * exceptions propagate as-is. Happy path bypasses recovery entirely.
 */
class ErrorRecoveryWorkflowTest {

    /** Domain exception simulating a missing-topic scenario. */
    static class MissingTopicException extends RuntimeException {
        MissingTopicException(String msg) { super(msg); }
    }

    // -------------------------------------------------------------------------
    // Reusable test steps
    // -------------------------------------------------------------------------

    private static final Step<String, String> RECOVERY_STEP =
            Step.named("recovery", (ctx, in) -> "recovered: topic inferred from context");

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void errorPathShouldRouteToRecoveryStep() {
        // LC4j: sequenceBuilder().first(risky.onError(Missing, recovery))
        // Our DSL: .step(risky).onError(MissingTopicException.class, recovery)
        Step<String, String> riskyStep = Step.named("risky", (ctx, in) -> {
            throw new MissingTopicException("no topic in: " + in);
        });

        String result = Workflow.<String, String>define("error-recovery")
                .step(riskyStep)
                    .onError(MissingTopicException.class, RECOVERY_STEP)
                .run("empty-document");

        assertThat(result).isEqualTo("recovered: topic inferred from context");
    }

    @Test
    void happyPathShouldBypassRecovery() {
        Step<String, String> riskyStep = Step.named("risky", (ctx, in) -> "processed: " + in);

        String result = Workflow.<String, String>define("error-recovery")
                .step(riskyStep)
                    .onError(MissingTopicException.class, RECOVERY_STEP)
                .run("valid-document");

        assertThat(result).isEqualTo("processed: valid-document");
    }

    @Test
    void unmatchedExceptionShouldPropagateUnchanged() {
        Step<String, String> riskyStep = Step.named("risky", (ctx, in) -> {
            throw new IllegalStateException("unrelated error");
        });

        // Recovery only handles MissingTopicException — IllegalStateException propagates
        assertThatThrownBy(() -> Workflow.<String, String>define("error-recovery")
                .step(riskyStep)
                    .onError(MissingTopicException.class, RECOVERY_STEP)
                .run("input"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unrelated error");
    }

    @Test
    void multipleErrorHandlersShouldMatchByType() {
        // Multiple .onError() clauses — each handles a different exception type
        Step<String, String> topicRecovery = Step.named("topic-recovery", (ctx, in) -> "topic-recovered");
        Step<String, String> formatRecovery = Step.named("format-recovery", (ctx, in) -> "format-recovered");

        // Throw MissingTopicException → routes to topicRecovery
        Step<String, String> throwsTopic = Step.named("risky", (ctx, in) -> {
            throw new MissingTopicException("no topic");
        });

        String result = Workflow.<String, String>define("multi-error")
                .step(throwsTopic)
                    .onError(MissingTopicException.class, topicRecovery)
                    .onError(IllegalArgumentException.class, formatRecovery)
                .run("input");

        assertThat(result).isEqualTo("topic-recovered");
    }
}
