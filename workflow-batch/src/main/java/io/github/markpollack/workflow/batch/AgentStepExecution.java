package io.github.markpollack.workflow.batch;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Tracks the execution of a single workflow step. Adapted from tuvium-batch-core's
 * {@code StepExecution} — drops Spring Batch's 8 counters and adds LLM-specific fields.
 *
 * <p>
 * Checkpoint key is {@code (runId, stepName)}. The {@link io.github.markpollack.workflow.batch.CheckpointingStepRunner}
 * queries by this key to decide whether to skip an already-completed step on restart.
 *
 * <p>
 * Concrete class (not abstract). SINGLE_TABLE inheritance can be added later if
 * domain-specific subclasses are needed.
 */
@Entity
@Table(name = "agent_step_executions", uniqueConstraints = {
		@UniqueConstraint(name = "uk_step_exec_run_step", columnNames = { "run_id", "step_name" })
})
public class AgentStepExecution {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.TIME)
	private UUID id;

	@Version
	private Integer version;

	@Column(name = "run_id", nullable = false)
	private String runId;

	@Column(name = "step_name", nullable = false)
	private String stepName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private BatchStatus status = BatchStatus.STARTING;

	@Embedded
	@AttributeOverrides({
			@AttributeOverride(name = "exitCode",
					column = @Column(name = "exit_code", length = 2500, nullable = false)),
			@AttributeOverride(name = "exitDescription",
					column = @Column(name = "exit_description", columnDefinition = "TEXT", nullable = false))
	})
	private ExitStatus exitStatus = ExitStatus.UNKNOWN;

	@Column(name = "tokens_used", nullable = false)
	private long tokensUsed;

	@Column(name = "tool_call_count", nullable = false)
	private int toolCallCount;

	@Column(name = "output_type", length = 512)
	private String outputType;

	@Column(name = "output_payload", columnDefinition = "TEXT")
	private String outputPayload;

	@Column(name = "cost_usd", nullable = false)
	private double costUsd;

	@Column(name = "create_time", nullable = false)
	private Instant createTime = Instant.now();

	@Column(name = "start_time")
	private Instant startTime;

	@Column(name = "end_time")
	private Instant endTime;

	@UpdateTimestamp
	@Column(name = "last_updated", nullable = false)
	private Instant lastUpdated;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	protected AgentStepExecution() {
	}

	public AgentStepExecution(String runId, String stepName) {
		this.runId = runId;
		this.stepName = stepName;
	}

	public UUID getId() {
		return this.id;
	}

	public Integer getVersion() {
		return this.version;
	}

	public String getRunId() {
		return this.runId;
	}

	public String getStepName() {
		return this.stepName;
	}

	public BatchStatus getStatus() {
		return this.status;
	}

	/**
	 * Upgrade the status using {@link BatchStatus#upgradeTo(BatchStatus)}. The status can
	 * only move forward in severity — it can never downgrade.
	 * @param newStatus the desired new status
	 */
	public void upgradeStatus(BatchStatus newStatus) {
		this.status = this.status.upgradeTo(newStatus);
	}

	public ExitStatus getExitStatus() {
		return this.exitStatus;
	}

	public void setExitStatus(ExitStatus exitStatus) {
		this.exitStatus = exitStatus;
	}

	public long getTokensUsed() {
		return this.tokensUsed;
	}

	public void setTokensUsed(long tokensUsed) {
		this.tokensUsed = tokensUsed;
	}

	public int getToolCallCount() {
		return this.toolCallCount;
	}

	public void setToolCallCount(int toolCallCount) {
		this.toolCallCount = toolCallCount;
	}

	public String getOutputType() {
		return this.outputType;
	}

	public void setOutputType(String outputType) {
		this.outputType = outputType;
	}

	public String getOutputPayload() {
		return this.outputPayload;
	}

	public void setOutputPayload(String outputPayload) {
		this.outputPayload = outputPayload;
	}

	public double getCostUsd() {
		return this.costUsd;
	}

	public void setCostUsd(double costUsd) {
		this.costUsd = costUsd;
	}

	public Instant getCreateTime() {
		return this.createTime;
	}

	public Instant getStartTime() {
		return this.startTime;
	}

	public void setStartTime(Instant startTime) {
		this.startTime = startTime;
	}

	public Instant getEndTime() {
		return this.endTime;
	}

	public void setEndTime(Instant endTime) {
		this.endTime = endTime;
	}

	public Instant getLastUpdated() {
		return this.lastUpdated;
	}

	public String getErrorMessage() {
		return this.errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

}
