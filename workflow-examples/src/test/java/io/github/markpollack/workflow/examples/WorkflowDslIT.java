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
package io.github.markpollack.workflow.examples;

import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.steps.ClaudeStep;
import io.github.markpollack.workflow.flows.workflow.RunOptions;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that exercise the Workflow DSL with real LLM calls via
 * {@link ClaudeStep} (Claude CLI subprocess, Max plan auth — no API key).
 * <p>
 * These tests are NOT part of the default {@code mvn test} run.
 * Run with: {@code mvn verify -pl workflow-examples}
 * <p>
 * Automatically skipped when running inside a Claude Code session (nested
 * sessions are not allowed).
 */
@Tag("integration")
@DisabledIfEnvironmentVariable(named = "CLAUDECODE", matches = ".+",
        disabledReason = "Cannot nest Claude CLI sessions inside Claude Code")
class WorkflowDslIT {

    /**
     * Branch routing: Claude classifies input, deterministic predicate routes
     * to the correct branch.
     */
    @Test
    void branchRoutingShouldWorkWithLlmClassifier() {
        ClaudeStep classifier = ClaudeStep.of(
                "Classify the following text as either 'technical' or 'non-technical'. " +
                "Reply with ONLY one of those two words, nothing else.\n\nText: {input}");

        Step<String, String> technicalHandler = Step.named("technical",
                (ctx, in) -> "TECHNICAL: " + in);
        Step<String, String> nonTechnicalHandler = Step.named("non-technical",
                (ctx, in) -> "NON-TECHNICAL: " + in);

        String result = Workflow.<String, String>define("branch-it")
                .step(classifier)
                .branch(output -> output.toString().strip().equalsIgnoreCase("technical"))
                    .then(technicalHandler)
                    .otherwise(nonTechnicalHandler)
                .run("The new API endpoint uses REST with OAuth2 bearer tokens and returns JSON payloads");

        assertThat(result).startsWith("TECHNICAL:");
    }

    /**
     * Sequential multi-step: Claude generates content, then a second Claude step summarizes it.
     * Validates that data flows between ClaudeStep instances in a pipeline.
     */
    @Test
    void sequentialPipelineShouldPassDataBetweenSteps() {
        ClaudeStep writer = ClaudeStep.of(
                "Write exactly 2 sentences about the following topic. " +
                "Be concise.\n\nTopic: {input}");
        ClaudeStep summarizer = ClaudeStep.of(
                "Summarize the following text in exactly one sentence, " +
                "starting with 'Summary:'.\n\n{input}");

        String result = Workflow.<String, String>define("pipeline-it")
                .step(writer)
                .step(summarizer)
                .run("the benefits of unit testing in software development");

        assertThat(result)
                .isNotBlank()
                .containsIgnoringCase("summary");
    }

    /**
     * repeatUntilOutput with a real LLM writer step and deterministic scorer.
     * The loop exits when the scorer marks the output as approved.
     */
    @Test
    void repeatUntilOutputShouldConverge() {
        ClaudeStep writer = ClaudeStep.of(
                "Improve the following text to be more professional and polished. " +
                "Keep it under 3 sentences.\n\n{input}");

        // Deterministic scorer: first call passes through, second adds APPROVED prefix
        AtomicInteger callCount = new AtomicInteger(0);
        Step<String, String> scorer = Step.named("scorer", (ctx, in) -> {
            int count = callCount.incrementAndGet();
            return count >= 2 ? "APPROVED: " + in : in;
        });

        String result = Workflow.<String, String>define("loop-it")
                .step(writer)
                .repeatUntilOutput(output -> output.toString().startsWith("APPROVED"))
                    .step(scorer)
                .end()
                .run("testing is good", RunOptions.unlimited().withMaxIterations(10));

        assertThat(result).startsWith("APPROVED:");
        assertThat(callCount.get()).isEqualTo(2);
    }

    /**
     * backTo: Claude refines a draft, deterministic checker decides whether to loop back.
     * Validates that backTo() works with a real LLM step in the loop body.
     */
    @Test
    void backToShouldCycleWithRealLlmStep() {
        AtomicInteger iterations = new AtomicInteger(0);

        ClaudeStep refiner = ClaudeStep.of(
                "Make this text more concise (keep under 2 sentences): {input}");

        Step<String, String> checker = Step.named("checker", (ctx, in) -> {
            iterations.incrementAndGet();
            return in;
        });

        String result = Workflow.<String, String>define("backto-it")
                .step(refiner)
                .step(checker)
                .backTo("ClaudeStep", output -> iterations.get() < 2)
                .run("The process of writing software involves many different stages " +
                     "including requirements gathering, design, implementation, testing, " +
                     "and deployment, each of which requires careful attention to detail.");

        assertThat(result).isNotBlank();
        assertThat(iterations.get()).isEqualTo(2);
    }
}
