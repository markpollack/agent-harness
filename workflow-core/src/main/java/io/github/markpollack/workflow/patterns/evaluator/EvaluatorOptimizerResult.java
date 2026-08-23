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
package io.github.markpollack.workflow.patterns.evaluator;

import io.github.markpollack.workflow.core.LoopResult;
import io.github.markpollack.workflow.core.LoopStatus;
import io.github.markpollack.workflow.core.TerminationReason;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Result of executing an EvaluatorOptimizerLoop (Reflexion pattern).
 * <p>
 * Provides access to common result data via the LoopResult interface,
 * plus pattern-specific data such as trial records and score progression.
 * <p>
 * {@code bestScore} is absent when no trial measured anything — no jury was configured, or every
 * jury that ran abstained, errored or reported an outcome without a measurement. That is a
 * different fact from a best score of {@code 0.0}, which means a jury looked and found nothing
 * good, and the two must stay distinguishable.
 */
public record EvaluatorOptimizerResult(
        // Common fields (from LoopResult)
        String runId,
        String output,
        LoopStatus status,
        TerminationReason reason,
        int turnsCompleted,
        Duration totalDuration,
        long totalTokens,
        double estimatedCost,

        // Pattern-specific fields
        List<EvaluatorOptimizerLoop.TrialRecord> trials,
        OptionalDouble bestScore,
        @Nullable String bestReflection
) implements LoopResult {

    /**
     * Returns the total number of trials executed.
     */
    public int totalTrials() {
        return trials.size();
    }

    /**
     * Returns the score improvement from first to best trial, or empty when the first trial
     * measured nothing and there is therefore no baseline to improve on.
     */
    public OptionalDouble scoreImprovement() {
        if (trials.isEmpty()) return OptionalDouble.empty();
        OptionalDouble first = trials.get(0).score();
        return first.isPresent() && bestScore.isPresent()
                ? OptionalDouble.of(bestScore.getAsDouble() - first.getAsDouble())
                : OptionalDouble.empty();
    }

    /**
     * Returns true if the score threshold was met.
     */
    public boolean thresholdMet() {
        return reason() == TerminationReason.SCORE_THRESHOLD_MET;
    }

    /**
     * Returns true if the loop terminated because it was stuck (no improvement).
     */
    public boolean wasStuck() {
        return reason() == TerminationReason.STUCK_DETECTED;
    }

    /**
     * Returns the best trial by score, or empty when no trial measured anything — there is no
     * best among trials that were never scored.
     */
    public Optional<EvaluatorOptimizerLoop.TrialRecord> bestTrial() {
        if (bestScore.isEmpty()) return Optional.empty();
        return trials.stream()
                .filter(t -> t.score().isPresent() && t.score().getAsDouble() == bestScore.getAsDouble())
                .findFirst();
    }

    /**
     * Returns the first trial record.
     */
    public Optional<EvaluatorOptimizerLoop.TrialRecord> firstTrial() {
        return trials.isEmpty() ? Optional.empty() : Optional.of(trials.get(0));
    }

    /**
     * Returns the last trial record.
     */
    public Optional<EvaluatorOptimizerLoop.TrialRecord> lastTrial() {
        return trials.isEmpty() ? Optional.empty() : Optional.of(trials.get(trials.size() - 1));
    }

    /**
     * Returns true if any trial passed the evaluation.
     */
    public boolean anyTrialPassed() {
        return trials.stream().anyMatch(EvaluatorOptimizerLoop.TrialRecord::passed);
    }

    /**
     * Creates a successful result.
     */
    public static EvaluatorOptimizerResult success(
            String runId,
            String output,
            int turns,
            Duration duration,
            long tokens,
            double cost,
            List<EvaluatorOptimizerLoop.TrialRecord> trials,
            OptionalDouble bestScore,
            @Nullable String bestReflection
    ) {
        return new EvaluatorOptimizerResult(
                runId,
                output,
                LoopStatus.COMPLETED,
                TerminationReason.SCORE_THRESHOLD_MET,
                turns,
                duration,
                tokens,
                cost,
                trials,
                bestScore,
                bestReflection
        );
    }

    /**
     * Creates a result with a specific termination reason.
     */
    public static EvaluatorOptimizerResult terminated(
            String runId,
            String output,
            TerminationReason reason,
            int turns,
            Duration duration,
            long tokens,
            double cost,
            List<EvaluatorOptimizerLoop.TrialRecord> trials,
            OptionalDouble bestScore,
            @Nullable String bestReflection
    ) {
        return new EvaluatorOptimizerResult(
                runId,
                output,
                LoopStatus.COMPLETED,
                reason,
                turns,
                duration,
                tokens,
                cost,
                trials,
                bestScore,
                bestReflection
        );
    }

    /**
     * Creates a failed result.
     */
    public static EvaluatorOptimizerResult failed(
            String runId,
            int turns,
            Duration duration
    ) {
        return new EvaluatorOptimizerResult(
                runId,
                null,
                LoopStatus.FAILED,
                TerminationReason.ERROR,
                turns,
                duration,
                0,
                0.0,
                List.of(),
                OptionalDouble.empty(),
                null
        );
    }
}
