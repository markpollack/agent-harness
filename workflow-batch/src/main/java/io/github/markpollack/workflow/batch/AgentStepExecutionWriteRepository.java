package io.github.markpollack.workflow.batch;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Targeted writes for {@link AgentStepExecution}. Uses JPQL {@code @Modifying} queries
 * that bypass the aggregate root to avoid {@code @Version} contention when multiple steps
 * run concurrently.
 *
 * <p>
 * All updates include {@code SET version = version + 1} and {@code SET lastUpdated = :now}
 * because {@code @UpdateTimestamp} does not apply to JPQL bulk updates (only to entity
 * dirty-check flush).
 *
 * <p>
 * Each method is {@code @Transactional} because this repository extends {@link Repository}
 * (not {@code JpaRepository}), so there is no inherited transaction demarcation.
 *
 * @see AgentStepExecutionReadRepository for read-only queries
 */
@Transactional
public interface AgentStepExecutionWriteRepository extends Repository<AgentStepExecution, UUID> {

	/**
	 * Persist a new step execution record. Called when a step starts for the first time.
	 */
	AgentStepExecution save(AgentStepExecution entity);

	/**
	 * Mark a step as completed with its output and metrics.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE AgentStepExecution s
			SET s.status = :status, s.exitStatus = :exitStatus,
			    s.outputPayload = :outputPayload,
			    s.tokensUsed = :tokensUsed, s.toolCallCount = :toolCallCount,
			    s.costUsd = :costUsd,
			    s.endTime = :endTime, s.lastUpdated = :now,
			    s.version = s.version + 1
			WHERE s.id = :stepId
			""")
	void updateCompleted(@Param("stepId") UUID stepId,
			@Param("status") BatchStatus status,
			@Param("exitStatus") ExitStatus exitStatus,
			@Param("outputPayload") String outputPayload,
			@Param("tokensUsed") long tokensUsed,
			@Param("toolCallCount") int toolCallCount,
			@Param("costUsd") double costUsd,
			@Param("endTime") Instant endTime,
			@Param("now") Instant now);

	/**
	 * Mark a step as failed with an error message.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE AgentStepExecution s
			SET s.status = :status, s.exitStatus = :exitStatus,
			    s.errorMessage = :errorMessage,
			    s.endTime = :endTime, s.lastUpdated = :now,
			    s.version = s.version + 1
			WHERE s.id = :stepId
			""")
	void updateFailed(@Param("stepId") UUID stepId,
			@Param("status") BatchStatus status,
			@Param("exitStatus") ExitStatus exitStatus,
			@Param("errorMessage") String errorMessage,
			@Param("endTime") Instant endTime,
			@Param("now") Instant now);

}
