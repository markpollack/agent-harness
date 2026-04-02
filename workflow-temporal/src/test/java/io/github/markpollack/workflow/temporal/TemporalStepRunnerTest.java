package io.github.markpollack.workflow.temporal;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalStepRunnerTest {

	@Test
	void defaultConstructorShouldSetDefaults() {
		var runner = new TemporalStepRunner("agent-task-queue");
		assertThat(runner.getTaskQueue()).isEqualTo("agent-task-queue");
		assertThat(runner.getStartToCloseTimeout()).isEqualTo(Duration.ofMinutes(10));
		assertThat(runner.getHeartbeatTimeout()).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void customConstructorShouldSetValues() {
		var runner = new TemporalStepRunner("custom-queue",
				Duration.ofMinutes(30), Duration.ofMinutes(1));
		assertThat(runner.getTaskQueue()).isEqualTo("custom-queue");
		assertThat(runner.getStartToCloseTimeout()).isEqualTo(Duration.ofMinutes(30));
		assertThat(runner.getHeartbeatTimeout()).isEqualTo(Duration.ofMinutes(1));
	}

}
