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
package io.github.markpollack.workflow.examples.v2;

import io.github.markpollack.workflow.engine.InMemoryEventSink;
import io.github.markpollack.workflow.engine.WorkflowEvent;
import io.github.markpollack.workflow.engine.WorkflowEventType;
import io.github.markpollack.workflow.engine.WorkflowRunOutcome;
import io.github.markpollack.workflow.flows.spec.WorkflowSpecEmission;
import io.github.markpollack.workflow.spec.DecisionSpecNode;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.WorkflowSpecNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stage-4 real workflows executing through the v2 path (DSL → {@code .toSpec()} →
 * interpreter): the DD-18 two-handler proof (PR review with a subprocess operation),
 * the v1↔v2 equivalence (issue triage), event-stream inspectability, and the
 * <b>static-spec leading-indicator measurement</b> (R1/R3) as a hard deliverable.
 */
class RealWorkflowV2Test {

    // -------------------------------------------------------------------------
    // PR review — DD-18 (subprocess operation) + both decision arms
    // -------------------------------------------------------------------------

    @Test
    void prReviewApprovesAnOrdinaryChangeThroughASubprocessOperation() {
        assumeTrue(SubprocessOperationHandler.shellAvailable(), "POSIX shell required");
        InMemoryEventSink sink = new InMemoryEventSink();

        WorkflowRunOutcome outcome = PrReviewV2.run("feature-123", sink);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).asString().startsWith("APPROVED:");
        // the subprocess actually ran: fetch_diff dispatched and succeeded
        assertThat(sink.events()).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(WorkflowEventType.OPERATION_SUCCEEDED);
            assertThat(e.nodeId()).isEqualTo("fetch_diff");
        });
    }

    @Test
    void prReviewRequestsChangesForABigRefactor() {
        assumeTrue(SubprocessOperationHandler.shellAvailable(), "POSIX shell required");
        WorkflowRunOutcome outcome = PrReviewV2.run("big-refactor", new InMemoryEventSink());

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.result()).asString().startsWith("CHANGES_REQUESTED:");
    }

    @Test
    void prReviewEmitsAnInspectableEventStream() {
        assumeTrue(SubprocessOperationHandler.shellAvailable(), "POSIX shell required");
        InMemoryEventSink sink = new InMemoryEventSink();
        PrReviewV2.run("obs-1", sink);

        List<WorkflowEventType> types = sink.events().stream().map(WorkflowEvent::eventType).toList();
        // the stream tells the whole story: start → per-node dispatch/success → decision → terminal
        assertThat(types).startsWith(WorkflowEventType.WORKFLOW_STARTED);
        assertThat(types).endsWith(WorkflowEventType.WORKFLOW_COMPLETED);
        assertThat(types).contains(
                WorkflowEventType.OPERATION_DISPATCHED,
                WorkflowEventType.OPERATION_SUCCEEDED,
                WorkflowEventType.EDGE_SELECTED,
                WorkflowEventType.NODE_COMPLETED);
        // sequences are 1-based, contiguous, monotonic
        assertThat(sink.events()).extracting(WorkflowEvent::sequence).isSorted().doesNotHaveDuplicates();
        assertThat(sink.events().get(0).sequence()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Issue triage — v1 ↔ v2 equivalence
    // -------------------------------------------------------------------------

    @Test
    void issueTriageProducesEquivalentResultsOnV1AndV2() {
        for (String issue : List.of("app crashes on login", "please add dark mode")) {
            Object v1 = IssueTriageV2.runV1(issue);
            WorkflowRunOutcome v2 = IssueTriageV2.runV2(issue, new InMemoryEventSink());
            assertThat(v2.completed()).isTrue();
            assertThat(v2.result()).as("v1↔v2 for '%s'", issue).isEqualTo(v1);
        }
    }

    @Test
    void issueTriageRoutesBugsAndNonBugsDifferently() {
        assertThat(IssueTriageV2.runV2("segfault error", new InMemoryEventSink()).result())
                .asString().startsWith("FILED BUG:");
        assertThat(IssueTriageV2.runV2("add a setting", new InMemoryEventSink()).result())
                .asString().startsWith("ACKNOWLEDGED:");
    }

    // -------------------------------------------------------------------------
    // The static-spec leading indicator (R1/R3) — a hard, recorded measurement
    // -------------------------------------------------------------------------

    @Test
    void staticSpecMeasurement_controlFlowLivesInTheGraph() {
        Measure pr = measure("pr-review", PrReviewV2.emit());
        Measure triage = measure("issue-triage", IssueTriageV2.workflow().toSpec());

        // PR review: fetch(subprocess) + analyze + routing + approve + reject = 5 ops;
        // exactly one graph-visible decision (approve/reject).
        assertThat(pr.operations()).isEqualTo(5);
        assertThat(pr.decisions()).isEqualTo(1);

        // Issue triage: intake + routing + file_bug + acknowledge = 4 ops; one decision.
        assertThat(triage.operations()).isEqualTo(4);
        assertThat(triage.decisions()).isEqualTo(1);

        // THE R1/R3 FINDING (recorded in step-4.1 learnings): every meaningful branch is
        // a graph-visible decision node — control flow is in the graph, not hidden inside
        // a fat operation. Each workflow has ≥1 decision and terminates explicitly.
        for (Measure m : List.of(pr, triage)) {
            assertThat(m.decisions()).as("%s has graph-visible branching", m.name()).isGreaterThanOrEqualTo(1);
            assertThat(m.terminates()).as("%s terminates explicitly", m.name()).isGreaterThanOrEqualTo(1);
        }
    }

    private static Measure measure(String name, WorkflowSpecEmission emission) {
        int decisions = 0;
        int tasks = 0;
        int terminates = 0;
        for (WorkflowSpecNode node : emission.spec().nodes()) {
            if (node instanceof DecisionSpecNode) {
                decisions++;
            } else if (node instanceof TaskSpecNode) {
                tasks++;
            } else {
                terminates++;
            }
        }
        return new Measure(name, emission.spec().operations().size(), decisions, tasks, terminates);
    }

    private record Measure(String name, int operations, int decisions, int tasks, int terminates) {
    }
}
