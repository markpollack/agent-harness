package io.github.markpollack.workflow.batch;

import java.io.PrintWriter;
import java.io.StringWriter;

import jakarta.persistence.Embeddable;

/**
 * Machine-readable exit code plus human-readable description. Allows categorized exit
 * codes beyond just COMPLETED/FAILED (e.g., FAILED_RATE_LIMIT, FAILED_AUTH,
 * FAILED_NETWORK).
 *
 * <p>
 * Immutable value object modelled as a {@code record} and annotated {@link Embeddable}
 * for JPA embedding in {@code AgentFlowExecution} and {@code AgentStepExecution} entities.
 *
 * <p>
 * Ported from {@code com.tuvium.batch.ExitStatus}, originally extracted from
 * {@code org.springframework.batch.core.ExitStatus} (class to record).
 */
@Embeddable
public record ExitStatus(String exitCode, String exitDescription) implements Comparable<ExitStatus> {

	/** Unknown state. */
	public static final ExitStatus UNKNOWN = new ExitStatus("UNKNOWN");

	/** Processing is still taking place. */
	public static final ExitStatus EXECUTING = new ExitStatus("EXECUTING");

	/** Finished processing. */
	public static final ExitStatus COMPLETED = new ExitStatus("COMPLETED");

	/** No processing took place. */
	public static final ExitStatus NOOP = new ExitStatus("NOOP");

	/** Finished processing with an error. */
	public static final ExitStatus FAILED = new ExitStatus("FAILED");

	/** Finished processing with interrupted status. */
	public static final ExitStatus STOPPED = new ExitStatus("STOPPED");

	/**
	 * Compact constructor — normalizes {@code null} description to empty string.
	 */
	public ExitStatus {
		if (exitDescription == null) {
			exitDescription = "";
		}
	}

	/**
	 * Convenience constructor with just an exit code and empty description.
	 * @param exitCode the exit code
	 */
	public ExitStatus(String exitCode) {
		this(exitCode, "");
	}

	/**
	 * Combine two exit statuses. The resulting exit code is the one with higher severity.
	 * Custom codes (severity 7) always win over standard codes. Descriptions are
	 * concatenated.
	 * @param other the status to combine with
	 * @return a new {@link ExitStatus} with the combined result
	 */
	public ExitStatus and(ExitStatus other) {
		if (other == null) {
			return this;
		}
		ExitStatus result = addExitDescription(other.exitDescription);
		if (compareTo(other) < 0) {
			result = result.replaceExitCode(other.exitCode);
		}
		return result;
	}

	/**
	 * Append to the exit description. If there is already a description present, the two
	 * are concatenated with a semicolon. Returns a new instance — this instance is
	 * unchanged.
	 * @param description the description to append
	 * @return a new {@link ExitStatus} with the appended description
	 */
	public ExitStatus addExitDescription(String description) {
		if (hasText(exitDescription)) {
			if (hasText(description) && !exitDescription.equals(description)) {
				return new ExitStatus(exitCode, exitDescription + "; " + description);
			}
			return this;
		}
		return new ExitStatus(exitCode, description);
	}

	/**
	 * Extract the stack trace from the throwable and append it to the description.
	 * @param throwable the throwable whose stack trace to append
	 * @return a new {@link ExitStatus} with the stack trace appended
	 */
	public ExitStatus addExitDescription(Throwable throwable) {
		StringWriter writer = new StringWriter();
		throwable.printStackTrace(new PrintWriter(writer));
		return addExitDescription(writer.toString());
	}

	/**
	 * Replace the exit code, preserving the description.
	 * @param code the new exit code
	 * @return a new {@link ExitStatus} with the replaced code
	 */
	public ExitStatus replaceExitCode(String code) {
		return new ExitStatus(code, exitDescription);
	}

	/**
	 * Compare by severity. Higher severity is "greater". Custom codes (severity 7) win
	 * over standard codes. Within the same severity, alphabetical order of exit code
	 * breaks ties.
	 *
	 * <p>
	 * Severity mapping:
	 * <ul>
	 * <li>EXECUTING: 1</li>
	 * <li>COMPLETED: 2</li>
	 * <li>NOOP: 3</li>
	 * <li>STOPPED: 4</li>
	 * <li>FAILED: 5</li>
	 * <li>UNKNOWN: 6</li>
	 * <li>Custom codes: 7</li>
	 * </ul>
	 */
	@Override
	public int compareTo(ExitStatus other) {
		if (severity(other) > severity(this)) {
			return -1;
		}
		if (severity(other) < severity(this)) {
			return 1;
		}
		return this.exitCode.compareTo(other.exitCode);
	}

	private static int severity(ExitStatus status) {
		if (status.exitCode.startsWith(EXECUTING.exitCode)) {
			return 1;
		}
		if (status.exitCode.startsWith(COMPLETED.exitCode)) {
			return 2;
		}
		if (status.exitCode.startsWith(NOOP.exitCode)) {
			return 3;
		}
		if (status.exitCode.startsWith(STOPPED.exitCode)) {
			return 4;
		}
		if (status.exitCode.startsWith(FAILED.exitCode)) {
			return 5;
		}
		if (status.exitCode.startsWith(UNKNOWN.exitCode)) {
			return 6;
		}
		return 7;
	}

	private static boolean hasText(String str) {
		return str != null && !str.isBlank();
	}

}
