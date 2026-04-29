package io.github.markpollack.workflow.batch;

import java.util.List;

/**
 * Operator API for inspecting and resetting checkpoint state before a retry.
 *
 * <p>When a run fails mid-execution, {@link BatchStatus#FAILED} records remain in the
 * database until an operator explicitly decides whether to retry. This separation is
 * intentional — not all failures are transient. A bad prompt, schema mismatch, or
 * programming error will fail again without a fix, so the system does not auto-retry.
 *
 * <p>Typical crash-recovery flow:
 * <ol>
 *   <li>Run crashes. FAILED record(s) remain; COMPLETED records are preserved.</li>
 *   <li>Operator calls {@link #getRunState(String)} to inspect what failed.</li>
 *   <li>Operator diagnoses root cause. If transient, calls
 *       {@link #resetFailedSteps(String)}.</li>
 *   <li>Operator re-submits the workflow with the same {@code runId}. COMPLETED steps
 *       are skipped; previously FAILED steps execute again.</li>
 * </ol>
 */
public class CheckpointManager {

	private final AgentStepExecutionReadRepository readRepo;

	private final AgentStepExecutionWriteRepository writeRepo;

	public CheckpointManager(AgentStepExecutionReadRepository readRepo,
			AgentStepExecutionWriteRepository writeRepo) {
		this.readRepo = readRepo;
		this.writeRepo = writeRepo;
	}

	/**
	 * Returns all step executions for a run. Use this to inspect what completed, what
	 * failed, and what never ran before deciding whether to retry.
	 *
	 * @param runId the workflow run to inspect
	 * @return all step execution records for this run, in insertion order
	 */
	public List<AgentStepExecution> getRunState(String runId) {
		return readRepo.findByRunId(runId);
	}

	/**
	 * Deletes all {@link BatchStatus#FAILED} records for a run so they will be
	 * re-executed on the next attempt. {@link BatchStatus#COMPLETED} records are
	 * untouched — those steps will still be skipped.
	 *
	 * <p><strong>Call this only after confirming the failure was transient</strong>
	 * (network blip, rate-limit, flaky external service). If the root cause is a
	 * programming error or bad configuration, fix that first — otherwise the retry
	 * will fail again.
	 *
	 * @param runId the workflow run to reset
	 * @return the number of FAILED step records that were removed
	 */
	public int resetFailedSteps(String runId) {
		List<AgentStepExecution> steps = readRepo.findByRunId(runId);
		int count = 0;
		for (AgentStepExecution step : steps) {
			if (step.getStatus() == BatchStatus.FAILED) {
				writeRepo.deleteById(step.getId());
				count++;
			}
		}
		return count;
	}

}
