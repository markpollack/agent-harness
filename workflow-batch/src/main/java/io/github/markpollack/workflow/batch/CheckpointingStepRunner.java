package io.github.markpollack.workflow.batch;

import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.StepRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StepRunner} that persists step outputs as checkpoints to JDBC. On restart,
 * completed steps are automatically skipped — the cached output is returned without
 * re-executing the step.
 *
 * <p>
 * This is the "Level 1" graduation path: crash recovery with zero new infrastructure
 * (any JDBC {@code DataSource} works). Same workflow code as {@code LocalStepRunner},
 * different {@code @Bean}.
 *
 * <p>
 * Checkpoint key is {@code (runId, stepName)}, sourced from
 * {@link AgentContext#runId()} and {@link Step#name()}.
 */
public final class CheckpointingStepRunner implements StepRunner {

	private static final Logger log = LoggerFactory.getLogger(CheckpointingStepRunner.class);

	private final AgentStepExecutionReadRepository readRepo;

	private final AgentStepExecutionWriteRepository writeRepo;

	private final ObjectMapper objectMapper;

	public CheckpointingStepRunner(AgentStepExecutionReadRepository readRepo,
			AgentStepExecutionWriteRepository writeRepo, ObjectMapper objectMapper) {
		this.readRepo = readRepo;
		this.writeRepo = writeRepo;
		this.objectMapper = objectMapper;
	}

	public CheckpointingStepRunner(AgentStepExecutionReadRepository readRepo,
			AgentStepExecutionWriteRepository writeRepo) {
		this(readRepo, writeRepo, new ObjectMapper());
	}

	@Override
	@SuppressWarnings("unchecked")
	public <I, O> O execute(Step<I, O> step, AgentContext ctx, I input) {
		String runId = ctx.runId();
		String stepName = step.name();

		// Check for existing checkpoint
		var existing = readRepo.findByRunIdAndStepName(runId, stepName);
		if (existing.isPresent() && existing.get().getStatus() == BatchStatus.COMPLETED) {
			log.debug("Step '{}' already completed for run '{}' — returning cached output", stepName, runId);
			return (O) deserializeOutput(existing.get().getOutputPayload());
		}

		// Create STARTED record
		var execution = new AgentStepExecution(runId, stepName);
		execution.upgradeStatus(BatchStatus.STARTED);
		execution.setStartTime(Instant.now());
		execution = writeRepo.save(execution);

		try {
			O output = step.execute(ctx, input);

			// Persist completed checkpoint
			var now = Instant.now();
			String payload = serializeOutput(output);
			writeRepo.updateCompleted(
					execution.getId(),
					BatchStatus.COMPLETED,
					ExitStatus.COMPLETED,
					payload,
					0L, 0, 0.0,
					now, now);

			log.debug("Step '{}' completed for run '{}', output checkpointed", stepName, runId);
			return output;
		}
		catch (Exception e) {
			// Record failure
			var now = Instant.now();
			writeRepo.updateFailed(
					execution.getId(),
					BatchStatus.FAILED,
					ExitStatus.FAILED.addExitDescription(e.getMessage()),
					e.getMessage(),
					now, now);

			if (e instanceof RuntimeException re) {
				throw re;
			}
			throw new RuntimeException("Step '" + stepName + "' failed", e);
		}
	}

	private String serializeOutput(Object output) {
		if (output == null) {
			return null;
		}
		if (output instanceof String s) {
			return s;
		}
		try {
			return objectMapper.writeValueAsString(output);
		}
		catch (JsonProcessingException e) {
			log.warn("Failed to serialize step output as JSON, storing toString(): {}", e.getMessage());
			return output.toString();
		}
	}

	private Object deserializeOutput(String payload) {
		if (payload == null) {
			return null;
		}
		// For String outputs (the common case in agent workflows), return as-is
		// JSON objects would need type information to deserialize properly
		return payload;
	}

}
