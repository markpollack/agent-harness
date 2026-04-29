package io.github.markpollack.workflow.journal;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.workflow.flows.workflow.StepTransition;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;
import io.github.markpollack.workflow.patterns.graph.NodeType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link TraceRecorder} that writes each step completion into a journal {@link Run}.
 * <p>
 * Bridges agent-workflow's execution tracing with agent-journal's run ledger. Each
 * {@link StepTransition} is recorded as a {@code CustomEvent} with type
 * {@code "workflow_step"} until journal-core adds {@code WorkflowStepEvent} to its
 * sealed {@code JournalEvent} hierarchy (see ROADMAP Step 2.5).
 *
 * <h2>Metrics recorded per step</h2>
 * <ul>
 *   <li>{@code workflow.steps.total} — incremented after every step</li>
 *   <li>{@code workflow.steps.agent} — incremented for AGENT-type steps only</li>
 *   <li>{@code workflow.cost.usd} — accumulated cost (when available)</li>
 *   <li>{@code workflow.duration.ms.<stepName>} — per-step wall-clock time</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (Run run = Journal.run("bom-sync").start()) {
 *     WorkflowExecutor executor = new WorkflowExecutor(
 *         new LocalStepRunner(),
 *         WorkflowJournal.forRun(run)
 *     );
 *     MyState result = Workflow.<MyState, MyState>define("bom-sync")
 *         .withExecutor(executor)
 *         .step(fetchVersions)
 *         .step(updateBom)
 *         .run(initialState);
 * }
 * }</pre>
 *
 * <p>{@link #getTrace(String)} returns an empty list — the journal is the source of
 * truth. Use {@link TraceRecorder#inMemory()} when trace replay is also needed.
 */
public final class JournalTraceRecorder implements TraceRecorder {

    private final Run run;

    JournalTraceRecorder(Run run) {
        this.run = Objects.requireNonNull(run, "run must not be null");
    }

    @Override
    public void record(StepTransition transition) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("workflowRunId", transition.workflowRunId());
        attributes.put("workflowName", transition.workflowName());
        attributes.put("stepName", transition.toStep());
        attributes.put("fromStep", transition.fromStep());
        attributes.put("nodeType", transition.nodeType().name());
        attributes.put("durationMs", transition.stepDuration().toMillis());
        attributes.put("tokensUsed", transition.tokensUsed());
        attributes.put("costUsd", transition.costUsd());
        if (transition.label() != null) {
            attributes.put("routingLabel", transition.label());
        }

        run.logEvent(CustomEvent.of("workflow_step", transition.timestamp(), attributes));

        run.logMetric("workflow.steps.total", 1);
        run.logMetric("workflow.duration.ms." + transition.toStep(),
                transition.stepDuration().toMillis());

        if (transition.nodeType() == NodeType.AGENT) {
            run.logMetric("workflow.steps.agent", 1);
            if (transition.costUsd() > 0) {
                run.logMetric("workflow.cost.usd", transition.costUsd());
            }
            if (transition.tokensUsed() > 0) {
                run.logMetric("workflow.tokens.used", transition.tokensUsed());
            }
        }
    }

    @Override
    public List<StepTransition> getTrace(String workflowRunId) {
        return List.of();
    }
}
