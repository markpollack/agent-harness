/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.agents.harness.patterns.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.harness.core.LoopState;
import org.springaicommunity.judge.context.ExecutionStatus;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.jury.Jury;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.score.Scores;
import org.springframework.ai.chat.model.ChatResponse;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter that bridges spring-ai-agents Judge/Jury framework with agent-harness.
 * <p>
 * This adapter enables using the rich spring-ai-agents judge ecosystem
 * (BuildSuccessJudge, FileExistsJudge, LLMJudge, etc.) within our agent loop patterns.
 * <p>
 * Key integration points:
 * <ul>
 *   <li>Converts LoopState to spring-ai-agents JudgmentContext</li>
 *   <li>Wraps synchronous Jury.vote() in reactive Mono</li>
 *   <li>Records observability metrics for judge execution</li>
 *   <li>Maps Verdict to termination decisions</li>
 * </ul>
 *
 * <p>Example usage with W&B-lite observability:
 * <pre>{@code
 * Jury jury = SimpleJury.builder()
 *     .judge(BuildSuccessJudge.compile(), 0.5)
 *     .judge(new FileExistsJudge(Path.of("output.txt")), 0.5)
 *     .votingStrategy(new WeightedAverageStrategy())
 *     .build();
 *
 * SpringAiJuryAdapter adapter = new SpringAiJuryAdapter(jury, observability);
 *
 * // In loop pattern:
 * Verdict verdict = adapter.evaluate(loopState, response, workingDir).block();
 * if (verdict.aggregated().pass()) {
 *     // Terminate loop
 * }
 * }</pre>
 */
public class SpringAiJuryAdapter {

    private static final Logger log = LoggerFactory.getLogger(SpringAiJuryAdapter.class);

    private final Jury jury;
    private final String juryName;

    public SpringAiJuryAdapter(Jury jury) {
        this(jury, "jury");
    }

    public SpringAiJuryAdapter(Jury jury, String juryName) {
        this.jury = jury;
        this.juryName = juryName;
    }

    /**
     * Evaluates the current loop state using the spring-ai-agents jury.
     * <p>
     * This is a synchronous call that executes all judges and aggregates their verdicts.
     *
     * @param state the current loop state
     * @param response the ChatResponse to evaluate (may be null)
     * @param workingDirectory the workspace directory for file-based judges
     * @return the verdict from the jury
     */
    public Verdict evaluate(LoopState state, ChatResponse response, Path workingDirectory) {
        long startTime = System.currentTimeMillis();

        try {
            // Extract output text from ChatResponse if available
            Optional<String> agentOutput = Optional.empty();
            if (response != null && response.getResult() != null) {
                var output = response.getResult().getOutput();
                if (output != null && output.getText() != null) {
                    agentOutput = Optional.of(output.getText());
                }
            }

            // Build spring-ai-agents JudgmentContext from LoopState
            JudgmentContext context = buildContext(state, workingDirectory, agentOutput);

            // Execute jury vote
            log.debug("{} evaluation started: runId={}, turn={}, judgeCount={}",
                    juryName, state.runId(), state.currentTurn(), jury.getJudges().size());

            Verdict verdict = jury.vote(context);

            // Log results
            long durationMs = System.currentTimeMillis() - startTime;
            double score = Scores.toNormalized(verdict.aggregated().score(), Map.of());

            log.debug("{} evaluation completed: runId={}, turn={}, passed={}, score={}, duration={}ms",
                    juryName, state.runId(), state.currentTurn(),
                    verdict.aggregated().pass(), score, durationMs);

            return verdict;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;

            log.error("{} evaluation failed: runId={}, turn={}, error={}, duration={}ms",
                    juryName, state.runId(), state.currentTurn(),
                    e.getMessage() != null ? e.getMessage() : "Unknown error", durationMs);

            throw new RuntimeException("Jury evaluation failed", e);
        }
    }

    /**
     * Builds a spring-ai-agents JudgmentContext from our LoopState.
     */
    private JudgmentContext buildContext(LoopState state, Path workingDirectory, Optional<String> agentOutput) {
        JudgmentContext.Builder builder = JudgmentContext.builder()
                .goal("Agent loop turn " + state.currentTurn())
                .workspace(workingDirectory)
                .executionTime(state.elapsed())
                .startedAt(state.startedAt())
                .status(state.abortSignalled() ? ExecutionStatus.CANCELLED : ExecutionStatus.SUCCESS);

        agentOutput.ifPresent(builder::agentOutput);

        // Add loop state metadata for judges that need it
        builder.metadata("runId", state.runId());
        builder.metadata("turn", state.currentTurn());
        builder.metadata("totalTokens", state.totalTokensUsed());
        builder.metadata("estimatedCost", state.estimatedCost());

        return builder.build();
    }

    /**
     * Returns true if the verdict indicates the loop should terminate successfully.
     */
    public boolean shouldTerminate(Verdict verdict) {
        return verdict.aggregated().pass();
    }

    /**
     * Returns the aggregated score from the verdict (0.0 - 1.0).
     */
    public double getScore(Verdict verdict) {
        return Scores.toNormalized(verdict.aggregated().score(), Map.of());
    }

    /**
     * Returns the underlying jury.
     */
    public Jury getJury() {
        return jury;
    }
}
