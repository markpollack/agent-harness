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
package org.springaicommunity.agents.harness.patterns.turnlimited;

import org.springaicommunity.agents.harness.core.LoopResult;
import org.springaicommunity.agents.harness.core.LoopState;
import org.springaicommunity.agents.harness.core.LoopStatus;
import org.springaicommunity.agents.harness.core.TerminationReason;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.score.Scores;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * Result of executing a TurnLimitedLoop.
 * <p>
 * Provides access to common result data via the LoopResult interface,
 * plus pattern-specific data such as the final loop state and last jury verdict.
 */
public record TurnLimitedResult(
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
        LoopState finalState,
        @Nullable Verdict lastVerdict
) implements LoopResult {

    /**
     * Returns the final score from the last jury evaluation, or 0.0 if no verdict.
     */
    public double finalScore() {
        if (lastVerdict == null) return 0.0;
        return Scores.toNormalized(lastVerdict.aggregated().score(), Map.of());
    }

    /**
     * Returns true if the jury passed on the final evaluation.
     */
    public boolean juryPassed() {
        return lastVerdict != null && lastVerdict.aggregated().pass();
    }

    /**
     * Returns true if the loop terminated due to stuck detection.
     */
    public boolean wasStuck() {
        return reason() == TerminationReason.STUCK_DETECTED;
    }

    /**
     * Returns true if the loop terminated due to max turns reached.
     */
    public boolean maxTurnsReached() {
        return reason() == TerminationReason.MAX_TURNS_REACHED;
    }

    /**
     * Returns true if the loop terminated due to timeout.
     */
    public boolean timedOut() {
        return reason() == TerminationReason.TIMEOUT;
    }

    /**
     * Returns true if the finish tool was called.
     */
    public boolean finishToolCalled() {
        return reason() == TerminationReason.FINISH_TOOL_CALLED;
    }

    /**
     * Creates a successful result.
     */
    public static TurnLimitedResult success(
            String runId,
            String output,
            LoopState state,
            @Nullable Verdict lastVerdict
    ) {
        return new TurnLimitedResult(
                runId,
                output,
                LoopStatus.COMPLETED,
                TerminationReason.FINISH_TOOL_CALLED,
                state.currentTurn(),
                state.elapsed(),
                state.totalTokensUsed(),
                state.estimatedCost(),
                state,
                lastVerdict
        );
    }

    /**
     * Creates a result with a specific termination reason.
     */
    public static TurnLimitedResult terminated(
            String runId,
            String output,
            TerminationReason reason,
            LoopState state,
            @Nullable Verdict lastVerdict
    ) {
        return new TurnLimitedResult(
                runId,
                output,
                LoopStatus.COMPLETED,
                reason,
                state.currentTurn(),
                state.elapsed(),
                state.totalTokensUsed(),
                state.estimatedCost(),
                state,
                lastVerdict
        );
    }

    /**
     * Creates a failed result.
     */
    public static TurnLimitedResult failed(String runId, LoopState state) {
        return new TurnLimitedResult(
                runId,
                null,
                LoopStatus.FAILED,
                TerminationReason.ERROR,
                state.currentTurn(),
                state.elapsed(),
                state.totalTokensUsed(),
                state.estimatedCost(),
                state,
                null
        );
    }
}
