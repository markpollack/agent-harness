package io.github.markpollack.workflow.batch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;

/**
 * Read-only query repository for {@link AgentStepExecution}. Extends {@link Repository}
 * (not {@code JpaRepository}) to enforce the aggregate boundary at compile time — no
 * {@code save()}, {@code delete()}, or other mutating methods are exposed.
 *
 * <p>
 * The two-tier pattern separates reads from writes to avoid {@code @Version} contention
 * when concurrent steps write to the database.
 *
 * @see AgentStepExecutionWriteRepository for targeted step writes
 */
public interface AgentStepExecutionReadRepository extends Repository<AgentStepExecution, UUID> {

	/**
	 * The checkpoint lookup. Used by {@code CheckpointingStepRunner} to decide whether to
	 * skip an already-completed step on restart.
	 */
	@QueryHints(@QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
	Optional<AgentStepExecution> findByRunIdAndStepName(String runId, String stepName);

	/**
	 * All steps for a given run. Useful for progress tracking and crash recovery inspection.
	 */
	@QueryHints(@QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true"))
	List<AgentStepExecution> findByRunId(String runId);

}
