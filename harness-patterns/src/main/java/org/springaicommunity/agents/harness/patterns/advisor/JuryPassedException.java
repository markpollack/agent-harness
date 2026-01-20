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
package org.springaicommunity.agents.harness.patterns.advisor;

import org.springaicommunity.agents.harness.core.LoopState;
import org.springaicommunity.agents.harness.core.TerminationReason;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.score.Scores;
import org.springframework.ai.chat.client.ChatClientResponse;

import java.util.Map;

/**
 * Exception thrown when jury evaluation passes (successful completion).
 * <p>
 * This is a "happy path" termination - the agent has successfully completed
 * the task as verified by the jury. It carries the verdict details for
 * reporting and analysis.
 */
public class JuryPassedException extends AgentLoopTerminatedException {

    private final Verdict verdict;

    /**
     * Creates a new jury passed exception.
     *
     * @param verdict the jury verdict
     * @param state the loop state at completion
     * @param response the final response
     */
    public JuryPassedException(Verdict verdict, LoopState state, ChatClientResponse response) {
        super(
                TerminationReason.SCORE_THRESHOLD_MET,
                formatMessage(verdict),
                state,
                response
        );
        this.verdict = verdict;
    }

    /**
     * Returns the jury verdict.
     */
    public Verdict getVerdict() {
        return verdict;
    }

    /**
     * Returns the aggregated score from the verdict as a normalized value (0.0 to 1.0).
     */
    public double getScore() {
        if (verdict == null || verdict.aggregated() == null || verdict.aggregated().score() == null) {
            return 0.0;
        }
        return Scores.toNormalized(verdict.aggregated().score(), Map.of());
    }

    private static String formatMessage(Verdict verdict) {
        if (verdict == null || verdict.aggregated() == null || verdict.aggregated().score() == null) {
            return "Jury passed";
        }
        double score = Scores.toNormalized(verdict.aggregated().score(), Map.of());
        return String.format("Jury passed with score %.2f", score);
    }
}
