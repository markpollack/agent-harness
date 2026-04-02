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
import jakarta.persistence.Version;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Tracks the execution of a complete workflow (flow). Adapted from tuvium-batch-core's
 * {@code JobExecution} — drops item-processing counters and adds workflow identity fields.
 *
 * <p>
 * Does NOT hold a collection of {@link AgentStepExecution}. Steps are loaded via
 * repository to avoid lazy loading issues and enable the two-tier write strategy.
 *
 * <p>
 * Concrete class (not abstract). SINGLE_TABLE inheritance can be added later if
 * domain-specific subclasses are needed.
 */
@Entity
@Table(name = "agent_flow_executions")
public class AgentFlowExecution {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.TIME)
	private UUID id;

	@Version
	private Integer version;

	@Column(name = "run_id", nullable = false, unique = true)
	private String runId;

	@Column(name = "workflow_name")
	private String workflowName;

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

	@Column(name = "create_time", nullable = false)
	private Instant createTime = Instant.now();

	@Column(name = "start_time")
	private Instant startTime;

	@Column(name = "end_time")
	private Instant endTime;

	@UpdateTimestamp
	@Column(name = "last_updated", nullable = false)
	private Instant lastUpdated;

	@Column(name = "steps_total", nullable = false)
	private int stepsTotal;

	@Column(name = "steps_completed", nullable = false)
	private int stepsCompleted;

	@Column(name = "total_cost_usd", nullable = false)
	private double totalCostUsd;

	protected AgentFlowExecution() {
	}

	public AgentFlowExecution(String runId, String workflowName) {
		this.runId = runId;
		this.workflowName = workflowName;
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

	public String getWorkflowName() {
		return this.workflowName;
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

	public int getStepsTotal() {
		return this.stepsTotal;
	}

	public void setStepsTotal(int stepsTotal) {
		this.stepsTotal = stepsTotal;
	}

	public int getStepsCompleted() {
		return this.stepsCompleted;
	}

	public void setStepsCompleted(int stepsCompleted) {
		this.stepsCompleted = stepsCompleted;
	}

	public double getTotalCostUsd() {
		return this.totalCostUsd;
	}

	public void setTotalCostUsd(double totalCostUsd) {
		this.totalCostUsd = totalCostUsd;
	}

}
