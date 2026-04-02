package io.github.markpollack.workflow.batch;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link AgentFlowExecution}. Uses {@link JpaRepository} (full CRUD) since
 * flow executions are not subject to the same concurrent write contention as step
 * executions — there is only one flow execution per run.
 */
public interface AgentFlowExecutionRepository extends JpaRepository<AgentFlowExecution, UUID> {

	Optional<AgentFlowExecution> findByRunId(String runId);

	Page<AgentFlowExecution> findByStatus(BatchStatus status, Pageable pageable);

}
