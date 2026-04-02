package io.github.markpollack.workflow.batch;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = RepositoryTest.TestConfig.class)
class RepositoryTest {

	@EnableAutoConfiguration
	static class TestConfig {
	}

	@Autowired
	private TestEntityManager em;

	@Autowired
	private AgentStepExecutionReadRepository readRepo;

	@Autowired
	private AgentStepExecutionWriteRepository writeRepo;

	@Autowired
	private AgentFlowExecutionRepository flowRepo;

	@Nested
	class StepReadRepository {

		@Test
		void findByRunIdAndStepNameShouldReturnCheckpoint() {
			var step = new AgentStepExecution("run-1", "classify");
			step.upgradeStatus(BatchStatus.STARTED);
			em.persistAndFlush(step);
			em.clear();

			var found = readRepo.findByRunIdAndStepName("run-1", "classify");
			assertThat(found).isPresent();
			assertThat(found.get().getStepName()).isEqualTo("classify");
		}

		@Test
		void findByRunIdAndStepNameShouldReturnEmptyWhenNotFound() {
			var found = readRepo.findByRunIdAndStepName("nonexistent", "step");
			assertThat(found).isEmpty();
		}

		@Test
		void findByRunIdShouldReturnAllStepsForRun() {
			em.persistAndFlush(new AgentStepExecution("run-1", "step-a"));
			em.persistAndFlush(new AgentStepExecution("run-1", "step-b"));
			em.persistAndFlush(new AgentStepExecution("run-2", "step-a"));
			em.clear();

			List<AgentStepExecution> steps = readRepo.findByRunId("run-1");
			assertThat(steps).hasSize(2);
			assertThat(steps).extracting(AgentStepExecution::getStepName)
					.containsExactlyInAnyOrder("step-a", "step-b");
		}
	}

	@Nested
	class StepWriteRepository {

		@Test
		void saveShouldPersistNewStepExecution() {
			var step = new AgentStepExecution("run-1", "classify");
			var saved = writeRepo.save(step);

			assertThat(saved.getId()).isNotNull();
			assertThat(saved.getVersion()).isNotNull();
		}

		@Test
		void updateCompletedShouldSetOutputAndMetrics() {
			var step = new AgentStepExecution("run-1", "classify");
			step.upgradeStatus(BatchStatus.STARTED);
			step.setStartTime(Instant.now());
			em.persistAndFlush(step);
			em.clear();

			var now = Instant.now();
			writeRepo.updateCompleted(
					step.getId(),
					BatchStatus.COMPLETED,
					ExitStatus.COMPLETED,
					"{\"result\":\"technical\"}",
					1500L, 7, 0.025,
					now, now);
			em.clear();

			var found = readRepo.findByRunIdAndStepName("run-1", "classify").orElseThrow();
			assertThat(found.getStatus()).isEqualTo(BatchStatus.COMPLETED);
			assertThat(found.getOutputPayload()).isEqualTo("{\"result\":\"technical\"}");
			assertThat(found.getTokensUsed()).isEqualTo(1500L);
			assertThat(found.getToolCallCount()).isEqualTo(7);
			assertThat(found.getCostUsd()).isEqualTo(0.025);
			assertThat(found.getEndTime()).isCloseTo(now, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
		}

		@Test
		void updateFailedShouldSetErrorMessage() {
			var step = new AgentStepExecution("run-1", "classify");
			step.upgradeStatus(BatchStatus.STARTED);
			em.persistAndFlush(step);
			em.clear();

			var now = Instant.now();
			writeRepo.updateFailed(
					step.getId(),
					BatchStatus.FAILED,
					ExitStatus.FAILED.addExitDescription("Connection timeout"),
					"Connection timeout",
					now, now);
			em.clear();

			var found = readRepo.findByRunIdAndStepName("run-1", "classify").orElseThrow();
			assertThat(found.getStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(found.getErrorMessage()).isEqualTo("Connection timeout");
			assertThat(found.getExitStatus().exitCode()).isEqualTo("FAILED");
			assertThat(found.getExitStatus().exitDescription()).contains("Connection timeout");
		}

		@Test
		void targetedUpdateShouldNotIncrementVersionViaOptimisticLock() {
			var step = new AgentStepExecution("run-1", "classify");
			step.upgradeStatus(BatchStatus.STARTED);
			em.persistAndFlush(step);
			int initialVersion = step.getVersion();
			em.clear();

			var now = Instant.now();
			writeRepo.updateCompleted(
					step.getId(), BatchStatus.COMPLETED, ExitStatus.COMPLETED,
					"{}", 0L, 0, 0.0, now, now);
			em.clear();

			// Version is incremented by our explicit SET, not by @Version optimistic lock
			var found = readRepo.findByRunIdAndStepName("run-1", "classify").orElseThrow();
			assertThat(found.getVersion()).isEqualTo(initialVersion + 1);
		}
	}

	@Nested
	class FlowRepository {

		@Test
		void saveShouldPersistNewFlowExecution() {
			var flow = new AgentFlowExecution("run-1", "my-workflow");
			var saved = flowRepo.save(flow);
			assertThat(saved.getId()).isNotNull();
			assertThat(saved.getVersion()).isNotNull();
		}

		@Test
		void findByRunIdShouldReturnFlow() {
			flowRepo.saveAndFlush(new AgentFlowExecution("run-1", "my-workflow"));
			em.clear();

			var found = flowRepo.findByRunId("run-1");
			assertThat(found).isPresent();
			assertThat(found.get().getWorkflowName()).isEqualTo("my-workflow");
		}

		@Test
		void findByStatusShouldFilterCorrectly() {
			var flow1 = new AgentFlowExecution("run-1", "wf-a");
			flow1.upgradeStatus(BatchStatus.STARTED);
			flowRepo.saveAndFlush(flow1);

			var flow2 = new AgentFlowExecution("run-2", "wf-b");
			flow2.upgradeStatus(BatchStatus.STARTED);
			flow2.upgradeStatus(BatchStatus.COMPLETED);
			flowRepo.saveAndFlush(flow2);
			em.clear();

			var page = flowRepo.findByStatus(BatchStatus.COMPLETED,
					org.springframework.data.domain.Pageable.unpaged());
			assertThat(page.getContent()).hasSize(1);
			assertThat(page.getContent().getFirst().getRunId()).isEqualTo("run-2");
		}
	}

	@Nested
	class SchemaValidation {

		@Test
		void uniqueConstraintOnRunIdAndStepNameShouldBeEnforced() {
			em.persistAndFlush(new AgentStepExecution("run-1", "classify"));

			org.junit.jupiter.api.Assertions.assertThrows(
					jakarta.persistence.PersistenceException.class,
					() -> {
						em.persistAndFlush(new AgentStepExecution("run-1", "classify"));
					});
		}

		@Test
		void uniqueConstraintOnFlowRunIdShouldBeEnforced() {
			flowRepo.saveAndFlush(new AgentFlowExecution("run-1", "wf-a"));

			org.junit.jupiter.api.Assertions.assertThrows(
					org.springframework.dao.DataIntegrityViolationException.class,
					() -> {
						flowRepo.saveAndFlush(new AgentFlowExecution("run-1", "wf-b"));
					});
		}
	}

}
