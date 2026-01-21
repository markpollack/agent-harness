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
package org.springaicommunity.agents.harness.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Immutable state of an agent loop at a point in time.
 * <p>
 * Tracks turn history and metrics for termination strategy evaluation.
 * Does not store the actual conversation messages - use Spring AI's
 * ChatMemory or the loop's internal message list for that.
 */
public record LoopState(
        String runId,
        int currentTurn,
        Instant startedAt,
        long totalTokensUsed,
        double estimatedCost,
        boolean abortSignalled,
        List<TurnSnapshot> turnHistory,
        int consecutiveSameOutputCount
) {
    /**
     * Creates an initial loop state.
     */
    public static LoopState initial(String runId) {
        return new LoopState(
                runId,
                0,
                Instant.now(),
                0L,
                0.0,
                false,
                List.of(),
                0
        );
    }

    /**
     * Returns a new state after completing a turn.
     *
     * @param tokensUsed tokens used in this turn
     * @param cost estimated cost of this turn
     * @param hasToolCalls whether this turn had tool calls
     * @param outputSignature hash of output for stuck detection
     */
    public LoopState completeTurn(long tokensUsed, double cost, boolean hasToolCalls, int outputSignature) {
        var newHistory = new ArrayList<>(turnHistory);
        var snapshot = new TurnSnapshot(currentTurn, tokensUsed, cost, hasToolCalls, outputSignature);
        newHistory.add(snapshot);

        // Check for stuck (same output repeated)
        int sameCount = calculateSameOutputCount(newHistory, outputSignature);

        return new LoopState(
                runId,
                currentTurn + 1,
                startedAt,
                totalTokensUsed + tokensUsed,
                estimatedCost + cost,
                abortSignalled,
                List.copyOf(newHistory),
                sameCount
        );
    }

    /**
     * Returns a new state with abort signalled.
     */
    public LoopState abort() {
        return new LoopState(
                runId,
                currentTurn,
                startedAt,
                totalTokensUsed,
                estimatedCost,
                true,
                turnHistory,
                consecutiveSameOutputCount
        );
    }

    /**
     * Returns the elapsed time since the loop started.
     */
    public Duration elapsed() {
        return Duration.between(startedAt, Instant.now());
    }

    /**
     * Returns the last turn snapshot if available.
     */
    public Optional<TurnSnapshot> lastTurn() {
        return turnHistory.isEmpty() ? Optional.empty() : Optional.of(turnHistory.getLast());
    }

    /**
     * Returns true if stuck (same output repeated threshold times).
     */
    public boolean isStuck(int threshold) {
        return consecutiveSameOutputCount >= threshold;
    }

    /**
     * Returns true if the cost limit has been exceeded.
     */
    public boolean costExceeded(double limit) {
        return limit > 0 && estimatedCost > limit;
    }

    /**
     * Returns true if timeout has been exceeded.
     */
    public boolean timeoutExceeded(Duration timeout) {
        return elapsed().compareTo(timeout) > 0;
    }

    /**
     * Returns true if max turns reached.
     */
    public boolean maxTurnsReached(int maxTurns) {
        return currentTurn >= maxTurns;
    }

    private int calculateSameOutputCount(List<TurnSnapshot> history, int currentSignature) {
        // Get the most recent turn (which was just added)
        TurnSnapshot currentTurn = history.getLast();

        // If this turn had tool calls, agent is making progress - not stuck
        if (currentTurn.hadToolCalls()) {
            return 0;
        }

        // Count consecutive turns with same signature AND no tool calls
        int count = 1;
        for (int i = history.size() - 2; i >= 0; i--) {
            TurnSnapshot turn = history.get(i);
            // Stop counting if: different signature OR had tool calls (making progress)
            if (turn.outputSignature() != currentSignature || turn.hadToolCalls()) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * Snapshot of a single turn for history tracking.
     */
    public record TurnSnapshot(
            int turn,
            long tokensUsed,
            double cost,
            boolean hadToolCalls,
            int outputSignature
    ) {}
}
