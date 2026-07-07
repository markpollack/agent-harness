package io.github.markpollack.workflow.engine;

/**
 * Receives canonical {@link WorkflowEvent}s during execution, in deterministic
 * execution order with monotonically increasing {@code sequence} per
 * {@code workflowRunId}. Each runtime designates <em>one canonical sink</em> for
 * conformance purposes (alpha spec §10) — ordering and commitment semantics are
 * evaluated relative to it.
 *
 * <p><b>Two roles, one interface</b> (DESIGN.md § WorkflowEventSink):
 * <ul>
 *   <li><b>Durable event journal</b> — transactional, part of progress correctness. In
 *       a durable interpreter, control-flow-relevant events are persisted in the same
 *       transaction as the checkpoint commit (§10 committed-event rule: execution never
 *       advances past an uncommitted control-flow event). {@code emit} is therefore
 *       synchronous: when it returns, the event is recorded per the runtime's
 *       commitment policy — a fire-and-forget implementation cannot be a journal.</li>
 *   <li><b>Observer sinks</b> — logging, controllers, external consumers. These receive
 *       events published <em>after</em> commit and are non-authoritative: an observer
 *       may miss a publish on crash, the journal never does; observers can replay from
 *       the journal. Commit first, publish second.</li>
 * </ul>
 *
 * <p>Implementations: {@link InMemoryEventSink} (canonical sink for non-durable
 * interpreters and tests); the JPA journal and post-commit observer fan-out arrive
 * with the durable interpreter (Stage 3).
 */
public interface WorkflowEventSink {

    /**
     * Accepts one canonical event. Must not reorder, drop, or mutate events.
     *
     * @param event the event to record or observe, never null
     */
    void emit(WorkflowEvent event);
}
