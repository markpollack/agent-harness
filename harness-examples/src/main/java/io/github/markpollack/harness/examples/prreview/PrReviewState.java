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
package io.github.markpollack.harness.examples.prreview;

import java.util.Objects;

/**
 * Accumulated state flowing through the PR review Blueprint pipeline.
 * <p>
 * Each node receives and returns a {@code PrReviewState}, progressively filling
 * in the fields. This models the "pipes-and-filters" style where state is carried
 * explicitly through the graph rather than held by an external orchestrator.
 *
 * @param prUrl                 the GitHub PR URL used to fetch data
 * @param rawPrData             raw PR data fetched from GitHub (JSON string)
 * @param conversationAnalysis  output of the conversation analysis agent node (null until filled)
 * @param riskAssessment        output of the risk assessment agent node (null until filled)
 * @param solutionAssessment    output of the solution assessment agent node (null until filled)
 * @param markdownReport        final assembled markdown report (null until assembled)
 */
public record PrReviewState(
        String prUrl,
        String rawPrData,
        String conversationAnalysis,
        String riskAssessment,
        String solutionAssessment,
        String markdownReport) {

    /**
     * Creates an initial state with only the PR URL.
     * All analysis fields are null; they are populated as each node executes.
     *
     * @param prUrl the GitHub PR URL
     * @return initial state
     */
    public static PrReviewState forUrl(String prUrl) {
        return new PrReviewState(Objects.requireNonNull(prUrl, "prUrl must not be null"),
                null, null, null, null, null);
    }

    /** Returns a copy with raw PR data populated. */
    public PrReviewState withRawData(String rawPrData) {
        return new PrReviewState(prUrl, rawPrData, conversationAnalysis, riskAssessment,
                solutionAssessment, markdownReport);
    }

    /** Returns a copy with conversation analysis result populated. */
    public PrReviewState withConversationAnalysis(String analysis) {
        return new PrReviewState(prUrl, rawPrData, analysis, riskAssessment,
                solutionAssessment, markdownReport);
    }

    /** Returns a copy with risk assessment result populated. */
    public PrReviewState withRiskAssessment(String assessment) {
        return new PrReviewState(prUrl, rawPrData, conversationAnalysis, assessment,
                solutionAssessment, markdownReport);
    }

    /** Returns a copy with solution assessment result populated. */
    public PrReviewState withSolutionAssessment(String assessment) {
        return new PrReviewState(prUrl, rawPrData, conversationAnalysis, riskAssessment,
                assessment, markdownReport);
    }

    /** Returns a copy with the final markdown report populated. */
    public PrReviewState withMarkdownReport(String report) {
        return new PrReviewState(prUrl, rawPrData, conversationAnalysis, riskAssessment,
                solutionAssessment, report);
    }
}
