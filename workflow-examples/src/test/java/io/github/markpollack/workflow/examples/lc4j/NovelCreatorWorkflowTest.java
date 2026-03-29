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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transliteration of LangChain4j's {@code sequenceBuilder(write, audienceEdit, styleEdit)} pattern.
 *
 * <h2>LC4j equivalent (collapsed)</h2>
 * <pre>
 * AiServices.create(WriteStoryAgent.class, model)       // write
 * AiServices.create(AudienceEditAgent.class, model)     // audienceEdit
 * AiServices.create(StyleEditAgent.class, model)        // styleEdit
 *
 * Workflow<String, String> pipeline = sequenceBuilder()
 *     .first(write)
 *     .then(audienceEdit)
 *     .then(styleEdit)
 *     .build();
 * pipeline.execute(topic);
 * </pre>
 *
 * <h2>Our DSL equivalent</h2>
 * <pre>
 * Workflow.define("novel-creator")
 *     .step(write)
 *     .then(audienceEdit)
 *     .then(styleEdit)
 *     .run(topic);
 * </pre>
 *
 * The DSL is a direct transliteration: same number of lines, same readability.
 * Advantage: typed composition; no factory objects; workflow is a {@link Step}
 * composable into larger pipelines.
 */
class NovelCreatorWorkflowTest {

    // -------------------------------------------------------------------------
    // Mocked steps — no LLM calls, track invocation order
    // -------------------------------------------------------------------------

    private static Step<String, String> trackingStep(String name, List<String> invocationLog) {
        return Step.named(name, (ctx, in) -> {
            invocationLog.add(name);
            return in + " [" + name + "]";
        });
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void sequentialThreeStepsShouldExecuteInOrder() {
        List<String> log = new ArrayList<>();

        Step<String, String> write = trackingStep("write", log);
        Step<String, String> audienceEdit = trackingStep("audience-edit", log);
        Step<String, String> styleEdit = trackingStep("style-edit", log);

        // Our DSL — direct transliteration of LC4j sequenceBuilder
        String result = Workflow.<String, String>define("novel-creator")
                .step(write)
                .then(audienceEdit)
                .then(styleEdit)
                .run("A story about robots");

        assertThat(log).containsExactly("write", "audience-edit", "style-edit");
        assertThat(result).contains("[write]", "[audience-edit]", "[style-edit]");
    }

    @Test
    void eachStepReceivesPreviousStepOutput() {
        // Each step appends its own tag to the running string.
        // The final output contains all three tags in sequence.
        String result = Workflow.<String, String>define("novel-creator")
                .step(Step.named("write",         (ctx, in) -> in + " | written"))
                .then(Step.named("audience-edit", (ctx, in) -> in + " | audience-edited"))
                .then(Step.named("style-edit",    (ctx, in) -> in + " | style-edited"))
                .run("topic");

        assertThat(result).isEqualTo("topic | written | audience-edited | style-edited");
    }

    @Test
    void workflowShouldBeComposableAsStep() {
        // Workflow implements Step — the entire pipeline can be nested inside
        // another workflow without adapters.
        Workflow<String, String> novelCreator = Workflow.<String, String>define("novel-creator")
                .step(Step.named("write",         (ctx, in) -> in + " | written"))
                .then(Step.named("audience-edit", (ctx, in) -> in + " | audience-edited"))
                .then(Step.named("style-edit",    (ctx, in) -> in + " | style-edited"))
                .build();

        String result = Workflow.<String, String>define("outer")
                .step(novelCreator)
                .then(Step.named("publish", (ctx, in) -> in + " | published"))
                .run("topic");

        assertThat(result).isEqualTo("topic | written | audience-edited | style-edited | published");
    }
}
