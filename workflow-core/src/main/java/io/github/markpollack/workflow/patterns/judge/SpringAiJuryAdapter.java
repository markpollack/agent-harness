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
package io.github.markpollack.workflow.patterns.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.judge.context.ExecutionStatus;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.Verdict;
import org.springframework.ai.chat.model.ChatResponse;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Adapter that bridges the agent-judge Judge/Jury framework with agent-workflow.
 * <p>
 * This adapter enables using the rich judge ecosystem (BuildSuccessJudge, FileExistsJudge,
 * LLMJudge, etc.) within our agent loop patterns.
 * <p>
 * It does exactly two things: it builds a {@link JudgmentContext} out of a {@link LoopState}, and
 * it runs the jury's vote synchronously, logging the outcome. There is no reactive wrapper — the
 * call blocks and returns a {@link Verdict} — and no metrics are recorded here; a caller that
 * wants observability instruments its own loop.
 *
 * <p>Reading the outcome is the caller's job and belongs at the caller, where the policy is:
 * {@code verdict.aggregated()} carries the status, and its {@code effectiveScore()} is present
 * only when the jury reached a finding.
 *
 * <p>Example usage:
 * <pre>{@code
 * Jury jury = SimpleJury.builder()
 *     .judge(BuildSuccessJudge.compile(), 0.5)
 *     .judge(new FileExistsJudge(Path.of("output.txt")), 0.5)
 *     .votingStrategy(new WeightedAverageStrategy())
 *     .build();
 *
 * SpringAiJuryAdapter adapter = new SpringAiJuryAdapter(jury, "build-health-jury");
 *
 * // In loop pattern:
 * Verdict verdict = adapter.evaluate(loopState, response, workingDir);
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
     * Evaluates the current loop state using the agent-judge jury.
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

            // Build the agent-judge JudgmentContext from LoopState
            JudgmentContext context = buildContext(state, workingDirectory, agentOutput);

            // Execute jury vote
            log.debug("{} evaluation started: runId={}, turn={}, judgeCount={}",
                    juryName, state.runId(), state.currentTurn(), jury.getJudges().size());

            Verdict verdict = jury.vote(context);

            // Log results
            long durationMs = System.currentTimeMillis() - startTime;

            log.debug("{} evaluation completed: runId={}, turn={}, status={}, score={}, duration={}ms",
                    juryName, state.runId(), state.currentTurn(), verdict.aggregated().status(),
                    ScoreText.describe(verdict.aggregated().effectiveScore()), durationMs);

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
     * Builds an agent-judge JudgmentContext from our LoopState.
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
     * Returns the underlying jury.
     */
    public Jury getJury() {
        return jury;
    }
}
