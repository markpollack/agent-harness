package io.github.markpollack.workflow.batch;

import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFlowExecutionTest {

	@Nested
	class Construction {

		@Test
		void shouldSetRunIdAndWorkflowName() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			assertThat(exec.getRunId()).isEqualTo("run-1");
			assertThat(exec.getWorkflowName()).isEqualTo("my-workflow");
		}

		@Test
		void shouldDefaultToStartingStatus() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			assertThat(exec.getStatus()).isEqualTo(BatchStatus.STARTING);
		}

		@Test
		void shouldDefaultToUnknownExitStatus() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			assertThat(exec.getExitStatus()).isEqualTo(ExitStatus.UNKNOWN);
		}

		@Test
		void shouldDefaultCountersToZero() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			assertThat(exec.getStepsTotal()).isZero();
			assertThat(exec.getStepsCompleted()).isZero();
			assertThat(exec.getTotalCostUsd()).isZero();
		}

		@Test
		void shouldSetCreateTime() {
			var before = Instant.now();
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			assertThat(exec.getCreateTime()).isAfterOrEqualTo(before);
		}
	}

	@Nested
	class StatusLifecycle {

		@Test
		void upgradeStatusShouldProgressForward() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			exec.upgradeStatus(BatchStatus.STARTED);
			assertThat(exec.getStatus()).isEqualTo(BatchStatus.STARTED);
		}

		@Test
		void upgradeStatusShouldNotDowngrade() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			exec.upgradeStatus(BatchStatus.FAILED);
			exec.upgradeStatus(BatchStatus.COMPLETED);
			assertThat(exec.getStatus()).isEqualTo(BatchStatus.FAILED);
		}

		@Test
		void normalProgressionShouldReachCompleted() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			exec.upgradeStatus(BatchStatus.STARTED);
			exec.upgradeStatus(BatchStatus.COMPLETED);
			assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		}
	}

	@Nested
	class ProgressTracking {

		@Test
		void shouldTrackStepProgress() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			exec.setStepsTotal(5);
			exec.setStepsCompleted(3);
			assertThat(exec.getStepsTotal()).isEqualTo(5);
			assertThat(exec.getStepsCompleted()).isEqualTo(3);
		}

		@Test
		void shouldTrackTotalCost() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			exec.setTotalCostUsd(1.50);
			assertThat(exec.getTotalCostUsd()).isEqualTo(1.50);
		}
	}

	@Nested
	class Timestamps {

		@Test
		void shouldTrackStartAndEndTimes() {
			var exec = new AgentFlowExecution("run-1", "my-workflow");
			var start = Instant.now();
			exec.setStartTime(start);
			var end = start.plusSeconds(30);
			exec.setEndTime(end);
			assertThat(exec.getStartTime()).isEqualTo(start);
			assertThat(exec.getEndTime()).isEqualTo(end);
		}
	}

}
