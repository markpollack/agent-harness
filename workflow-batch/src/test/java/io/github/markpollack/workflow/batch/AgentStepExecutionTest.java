package io.github.markpollack.workflow.batch;

import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStepExecutionTest {

	@Nested
	class Construction {

		@Test
		void shouldSetRunIdAndStepName() {
			var exec = new AgentStepExecution("run-1", "classify");
			assertThat(exec.getRunId()).isEqualTo("run-1");
			assertThat(exec.getStepName()).isEqualTo("classify");
		}

		@Test
		void shouldDefaultToStartingStatus() {
			var exec = new AgentStepExecution("run-1", "classify");
			assertThat(exec.getStatus()).isEqualTo(BatchStatus.STARTING);
		}

		@Test
		void shouldDefaultToUnknownExitStatus() {
			var exec = new AgentStepExecution("run-1", "classify");
			assertThat(exec.getExitStatus()).isEqualTo(ExitStatus.UNKNOWN);
		}

		@Test
		void shouldDefaultLlmFieldsToZero() {
			var exec = new AgentStepExecution("run-1", "classify");
			assertThat(exec.getTokensUsed()).isZero();
			assertThat(exec.getToolCallCount()).isZero();
			assertThat(exec.getCostUsd()).isZero();
		}

		@Test
		void shouldSetCreateTime() {
			var before = Instant.now();
			var exec = new AgentStepExecution("run-1", "classify");
			assertThat(exec.getCreateTime()).isAfterOrEqualTo(before);
		}
	}

	@Nested
	class StatusLifecycle {

		@Test
		void upgradeStatusShouldProgressForward() {
			var exec = new AgentStepExecution("run-1", "classify");
			exec.upgradeStatus(BatchStatus.STARTED);
			assertThat(exec.getStatus()).isEqualTo(BatchStatus.STARTED);
		}

		@Test
		void upgradeStatusShouldNotDowngrade() {
			var exec = new AgentStepExecution("run-1", "classify");
			exec.upgradeStatus(BatchStatus.FAILED);
			exec.upgradeStatus(BatchStatus.COMPLETED);
			assertThat(exec.getStatus()).isEqualTo(BatchStatus.FAILED);
		}

		@Test
		void normalProgressionShouldReachCompleted() {
			var exec = new AgentStepExecution("run-1", "classify");
			exec.upgradeStatus(BatchStatus.STARTED);
			exec.upgradeStatus(BatchStatus.COMPLETED);
			assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		}
	}

	@Nested
	class LlmFields {

		@Test
		void shouldTrackTokenUsage() {
			var exec = new AgentStepExecution("run-1", "classify");
			exec.setTokensUsed(1500);
			assertThat(exec.getTokensUsed()).isEqualTo(1500);
		}

		@Test
		void shouldTrackToolCallCount() {
			var exec = new AgentStepExecution("run-1", "classify");
			exec.setToolCallCount(7);
			assertThat(exec.getToolCallCount()).isEqualTo(7);
		}

		@Test
		void shouldTrackCost() {
			var exec = new AgentStepExecution("run-1", "classify");
			exec.setCostUsd(0.025);
			assertThat(exec.getCostUsd()).isEqualTo(0.025);
		}

		@Test
		void shouldStoreOutputPayload() {
			var exec = new AgentStepExecution("run-1", "classify");
			exec.setOutputPayload("{\"result\":\"technical\"}");
			assertThat(exec.getOutputPayload()).isEqualTo("{\"result\":\"technical\"}");
		}
	}

	@Nested
	class Timestamps {

		@Test
		void shouldTrackStartAndEndTimes() {
			var exec = new AgentStepExecution("run-1", "classify");
			var start = Instant.now();
			exec.setStartTime(start);
			var end = start.plusSeconds(5);
			exec.setEndTime(end);
			assertThat(exec.getStartTime()).isEqualTo(start);
			assertThat(exec.getEndTime()).isEqualTo(end);
		}
	}

	@Nested
	class ErrorTracking {

		@Test
		void shouldStoreErrorMessage() {
			var exec = new AgentStepExecution("run-1", "classify");
			exec.setErrorMessage("Connection timeout");
			assertThat(exec.getErrorMessage()).isEqualTo("Connection timeout");
		}

		@Test
		void errorMessageShouldDefaultToNull() {
			var exec = new AgentStepExecution("run-1", "classify");
			assertThat(exec.getErrorMessage()).isNull();
		}
	}

}
