package io.github.markpollack.workflow.batch;

/**
 * Unified status for both flow and step executions. Values are ordered by severity —
 * ordinal position matters for {@link #max(BatchStatus, BatchStatus)} aggregation.
 *
 * <p>
 * Only if ALL steps are COMPLETED can the flow be COMPLETED. If any step is FAILED, the
 * aggregate is FAILED. {@link #max(BatchStatus, BatchStatus)} computes the worst outcome.
 *
 * <p>
 * Ported from {@code com.tuvium.batch.BatchStatus}, originally extracted from
 * {@code org.springframework.batch.core.BatchStatus}.
 */
public enum BatchStatus {

	/** Finished successfully (lowest severity). */
	COMPLETED,

	/** Transitioning to active. */
	STARTING,

	/** Currently executing. */
	STARTED,

	/** Cancellation requested, finishing current work. */
	STOPPING,

	/** Gracefully cancelled. */
	STOPPED,

	/** Finished with error. */
	FAILED,

	/** Cannot be restarted. */
	ABANDONED,

	/** Indeterminate state (highest severity). */
	UNKNOWN;

	/**
	 * Return the higher-severity status. Used to aggregate step statuses into flow status.
	 * @param status1 first status
	 * @param status2 second status
	 * @return the status with higher ordinal (greater severity)
	 */
	public static BatchStatus max(BatchStatus status1, BatchStatus status2) {
		return status1.isGreaterThan(status2) ? status1 : status2;
	}

	/**
	 * {@code true} if STARTING, STARTED, or STOPPING.
	 * @return whether this status represents an active execution
	 */
	public boolean isRunning() {
		return this == STARTING || this == STARTED || this == STOPPING;
	}

	/**
	 * {@code true} if FAILED or higher severity.
	 * @return whether this status represents an unsuccessful outcome
	 */
	public boolean isUnsuccessful() {
		return this == FAILED || this.isGreaterThan(FAILED);
	}

	/**
	 * Move to a new status without downgrading. If either status is greater than STARTED,
	 * return the maximum (failures take priority). Otherwise, COMPLETED wins over
	 * STARTING/STARTED (normal progression).
	 * @param other the status to upgrade to
	 * @return the resulting status
	 */
	public BatchStatus upgradeTo(BatchStatus other) {
		if (isGreaterThan(STARTED) || other.isGreaterThan(STARTED)) {
			return max(this, other);
		}
		if (this == COMPLETED || other == COMPLETED) {
			return COMPLETED;
		}
		return max(this, other);
	}

	/**
	 * @param other a status to compare
	 * @return {@code true} if this has higher severity than {@code other}
	 */
	public boolean isGreaterThan(BatchStatus other) {
		return this.compareTo(other) > 0;
	}

	/**
	 * @param other a status to compare
	 * @return {@code true} if this has lower severity than {@code other}
	 */
	public boolean isLessThan(BatchStatus other) {
		return this.compareTo(other) < 0;
	}

}
