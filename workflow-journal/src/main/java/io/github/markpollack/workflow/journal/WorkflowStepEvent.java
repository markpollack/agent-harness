package io.github.markpollack.workflow.journal;

import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.workflow.patterns.graph.NodeType;

import java.time.Duration;
import java.time.Instant;

/**
 * Records a single step completion in a workflow execution.
 * <p>
 * Maps directly from {@link io.github.markpollack.workflow.flows.workflow.StepTransition}.
 * Stored in a journal {@link io.github.markpollack.journal.Run} via
 * {@link JournalTraceRecorder}.
 *
 * <p><strong>Design note</strong>: {@code JournalEvent} is a sealed interface in
 * journal-core. This record cannot implement it until {@code WorkflowStepEvent} is
 * added to the {@code permits} clause in journal-core and a new journal-core release
 * is cut. See ROADMAP Step 2.5 for the two-phase delivery plan.
 * <p>
 * Interim workaround (not yet implemented): wrap step data in {@code CustomEvent}
 * until journal-core is updated.
 *
 * @param timestamp       when this step completed
 * @param workflowRunId   the workflow run identifier
 * @param workflowName    the workflow name
 * @param stepName        the node that just executed
 * @param fromStep        the preceding node (null for the first step)
 * @param nodeType        DETERMINISTIC or AGENT
 * @param stepDuration    wall-clock time the step took
 * @param tokensUsed      tokens consumed (0 if not yet tracked at step level)
 * @param costUsd         estimated USD cost (0.0 if not yet tracked at step level)
 * @param routingLabel    branch/gate label, or null for sequential steps
 */
// TODO: add `implements JournalEvent` once journal-core adds WorkflowStepEvent to its sealed permits
public record WorkflowStepEvent(
        Instant timestamp,
        String workflowRunId,
        String workflowName,
        String stepName,
        String fromStep,
        NodeType nodeType,
        Duration stepDuration,
        long tokensUsed,
        double costUsd,
        String routingLabel
) {
    public String type() {
        return "workflow_step";
    }
}
