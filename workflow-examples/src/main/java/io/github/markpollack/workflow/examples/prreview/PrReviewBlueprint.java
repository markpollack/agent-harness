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
package io.github.markpollack.workflow.examples.prreview;

import io.github.markpollack.workflow.patterns.graph.Blueprint;
import io.github.markpollack.workflow.patterns.graph.GraphContext;
import io.github.markpollack.workflow.patterns.graph.GraphResult;

import java.util.function.Function;

/**
 * PR Review pipeline expressed as a {@link Blueprint}.
 * <p>
 * This is a structural equivalent of the {@code PrReviewAnalyzer} from the
 * agent-client demo — the same five-step pipeline, now declared as a Blueprint
 * so node types (deterministic vs agent) are explicit and per-node metrics are
 * collected automatically.
 *
 * <h2>Pipeline topology</h2>
 * <pre>
 * [deterministic] fetch-pr          — fetch raw PR JSON from GitHub API
 *       ↓
 * [agent]         analyze           — conversation analysis (reviews, requirements, consensus)
 *       ↓
 * [agent]         assess-risk       — security / breaking change / performance risk
 *       ↓
 * [agent]         assess-solutions  — code quality, architecture, completeness
 *       ↓
 * [deterministic] assemble-report   — render markdown report from all analysis results
 * </pre>
 *
 * <h2>Control-flow equivalence with PrReviewAnalyzer</h2>
 * <pre>
 * PrReviewAnalyzer                    PrReviewBlueprint
 * ─────────────────────────────────── ────────────────────────────────────────
 * loadPrData()                        fetch-pr node (DETERMINISTIC)
 * performConversationAnalysis(...)    analyze node (AGENT)
 * performRiskAssessment(...)          assess-risk node (AGENT)
 * performSolutionAssessment(...)      assess-solutions node (AGENT)
 * generateReport(...)                 assemble-report node (DETERMINISTIC)
 *
 * Differences:
 * - PrReviewAnalyzer carries shared state in local variables; Blueprint threads
 *   it explicitly via PrReviewState (immutable, copy-on-update).
 * - Blueprint auto-collects per-node timing; PrReviewAnalyzer has no metrics.
 * - Blueprint nodes are reusable / testable independently; PrReviewAnalyzer is
 *   a monolithic class.
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Construct with injectable agent functions (real or stub)
 * PrReviewBlueprint pipeline = new PrReviewBlueprint(
 *     url -> ghApi(url),                        // fetch-pr
 *     state -> callAgent("analyze", state),     // analyze
 *     state -> callAgent("assess-risk", state), // assess-risk
 *     state -> callAgent("assess-sols", state)  // assess-solutions
 * );
 *
 * GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");
 * if (result.isSuccess()) {
 *     String report = result.output().markdownReport();
 * }
 * }</pre>
 */
public class PrReviewBlueprint {

    private final Blueprint<PrReviewState, PrReviewState> blueprint;

    /**
     * Constructs the PR review pipeline with injectable step implementations.
     * <p>
     * All four functions are injectable so the pipeline is unit-testable without
     * live API calls.
     *
     * @param prDataFetcher       deterministic: fetches raw PR JSON given the URL in state
     * @param conversationAnalyzer agent: produces conversation analysis from state
     * @param riskAssessor        agent: produces risk assessment from state
     * @param solutionAssessor    agent: produces solution assessment from state
     */
    public PrReviewBlueprint(
            Function<PrReviewState, PrReviewState> prDataFetcher,
            Function<PrReviewState, PrReviewState> conversationAnalyzer,
            Function<PrReviewState, PrReviewState> riskAssessor,
            Function<PrReviewState, PrReviewState> solutionAssessor) {

        this.blueprint = Blueprint.<PrReviewState, PrReviewState>builder("pr-review")

                .deterministic("fetch-pr",
                        "Fetch PR data from GitHub API (gh api /repos/...)",
                        (GraphContext ctx, PrReviewState state) -> prDataFetcher.apply(state))

                .agentFunction("analyze",
                        "Conversation analysis: requirements, reviewer concerns, consensus",
                        (GraphContext ctx, PrReviewState state) -> conversationAnalyzer.apply(state))

                .agentFunction("assess-risk",
                        "Risk assessment: security, breaking changes, performance",
                        (GraphContext ctx, PrReviewState state) -> riskAssessor.apply(state))

                .agentFunction("assess-solutions",
                        "Solution assessment: code quality, architecture, completeness",
                        (GraphContext ctx, PrReviewState state) -> solutionAssessor.apply(state))

                .deterministic("assemble-report",
                        "Render final markdown report from accumulated analysis results",
                        (GraphContext ctx, PrReviewState state) -> assembleReport(state))

                .sequential()
                .build();
    }

    /**
     * Executes the PR review pipeline for the given PR URL.
     *
     * @param prUrl the GitHub PR URL
     * @return the graph result containing a {@link PrReviewState} with all analyses
     *         and the assembled markdown report, or a failure result with error details
     */
    public GraphResult<PrReviewState> run(String prUrl) {
        return blueprint.execute(PrReviewState.forUrl(prUrl));
    }

    /**
     * Returns the underlying blueprint for inspection (node list, metrics access, etc.).
     *
     * @return the blueprint
     */
    public Blueprint<PrReviewState, PrReviewState> blueprint() {
        return blueprint;
    }

    /**
     * Assembles the markdown report from all analysis results in the state.
     * Deterministic: no LLM calls, pure string assembly.
     */
    private PrReviewState assembleReport(PrReviewState state) {
        String report = """
                # PR Review Report

                **PR**: %s

                ## Conversation Analysis

                %s

                ## Risk Assessment

                %s

                ## Solution Assessment

                %s
                """.formatted(
                state.prUrl(),
                nullToSection(state.conversationAnalysis(), "Conversation Analysis"),
                nullToSection(state.riskAssessment(), "Risk Assessment"),
                nullToSection(state.solutionAssessment(), "Solution Assessment"));

        return state.withMarkdownReport(report);
    }

    private String nullToSection(String value, String sectionName) {
        return value != null ? value : "_" + sectionName + " not available_";
    }
}
