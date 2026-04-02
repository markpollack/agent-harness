package io.github.markpollack.workflow.batch;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatchStatusTest {

	@Nested
	class SeverityOrdering {

		@Test
		void completedShouldBeLowestSeverity() {
			for (BatchStatus status : BatchStatus.values()) {
				if (status != BatchStatus.COMPLETED) {
					assertThat(BatchStatus.COMPLETED.isLessThan(status))
							.as("COMPLETED should be less than %s", status)
							.isTrue();
				}
			}
		}

		@Test
		void unknownShouldBeHighestSeverity() {
			for (BatchStatus status : BatchStatus.values()) {
				if (status != BatchStatus.UNKNOWN) {
					assertThat(BatchStatus.UNKNOWN.isGreaterThan(status))
							.as("UNKNOWN should be greater than %s", status)
							.isTrue();
				}
			}
		}

		@Test
		void ordinalOrderMatchesSeverity() {
			BatchStatus[] expected = {
				BatchStatus.COMPLETED, BatchStatus.STARTING, BatchStatus.STARTED,
				BatchStatus.STOPPING, BatchStatus.STOPPED, BatchStatus.FAILED,
				BatchStatus.ABANDONED, BatchStatus.UNKNOWN
			};
			assertThat(BatchStatus.values()).containsExactly(expected);
		}
	}

	@Nested
	class Max {

		@Test
		void maxShouldReturnHigherSeverity() {
			assertThat(BatchStatus.max(BatchStatus.COMPLETED, BatchStatus.FAILED))
					.isEqualTo(BatchStatus.FAILED);
			assertThat(BatchStatus.max(BatchStatus.FAILED, BatchStatus.COMPLETED))
					.isEqualTo(BatchStatus.FAILED);
		}

		@Test
		void maxWithSameStatusShouldReturnSame() {
			assertThat(BatchStatus.max(BatchStatus.STARTED, BatchStatus.STARTED))
					.isEqualTo(BatchStatus.STARTED);
		}
	}

	@Nested
	class IsRunning {

		@Test
		void startingShouldBeRunning() {
			assertThat(BatchStatus.STARTING.isRunning()).isTrue();
		}

		@Test
		void startedShouldBeRunning() {
			assertThat(BatchStatus.STARTED.isRunning()).isTrue();
		}

		@Test
		void stoppingShouldBeRunning() {
			assertThat(BatchStatus.STOPPING.isRunning()).isTrue();
		}

		@Test
		void completedShouldNotBeRunning() {
			assertThat(BatchStatus.COMPLETED.isRunning()).isFalse();
		}

		@Test
		void failedShouldNotBeRunning() {
			assertThat(BatchStatus.FAILED.isRunning()).isFalse();
		}
	}

	@Nested
	class IsUnsuccessful {

		@Test
		void failedShouldBeUnsuccessful() {
			assertThat(BatchStatus.FAILED.isUnsuccessful()).isTrue();
		}

		@Test
		void abandonedShouldBeUnsuccessful() {
			assertThat(BatchStatus.ABANDONED.isUnsuccessful()).isTrue();
		}

		@Test
		void unknownShouldBeUnsuccessful() {
			assertThat(BatchStatus.UNKNOWN.isUnsuccessful()).isTrue();
		}

		@Test
		void completedShouldNotBeUnsuccessful() {
			assertThat(BatchStatus.COMPLETED.isUnsuccessful()).isFalse();
		}

		@Test
		void startedShouldNotBeUnsuccessful() {
			assertThat(BatchStatus.STARTED.isUnsuccessful()).isFalse();
		}
	}

	@Nested
	class UpgradeTo {

		@Test
		void shouldUpgradeFromStartedToCompleted() {
			assertThat(BatchStatus.STARTED.upgradeTo(BatchStatus.COMPLETED))
					.isEqualTo(BatchStatus.COMPLETED);
		}

		@Test
		void shouldNotDowngradeFromCompletedToStarted() {
			assertThat(BatchStatus.COMPLETED.upgradeTo(BatchStatus.STARTED))
					.isEqualTo(BatchStatus.COMPLETED);
		}

		@Test
		void failureShouldTakePriorityOverCompleted() {
			assertThat(BatchStatus.COMPLETED.upgradeTo(BatchStatus.FAILED))
					.isEqualTo(BatchStatus.FAILED);
		}

		@Test
		void failureShouldTakePriorityOverStarted() {
			assertThat(BatchStatus.STARTED.upgradeTo(BatchStatus.FAILED))
					.isEqualTo(BatchStatus.FAILED);
		}

		@Test
		void stoppedShouldTakePriorityOverStarting() {
			assertThat(BatchStatus.STARTING.upgradeTo(BatchStatus.STOPPED))
					.isEqualTo(BatchStatus.STOPPED);
		}

		@Test
		void shouldUpgradeFromStartingToStarted() {
			assertThat(BatchStatus.STARTING.upgradeTo(BatchStatus.STARTED))
					.isEqualTo(BatchStatus.STARTED);
		}
	}

	@Nested
	class Comparison {

		@Test
		void isGreaterThanShouldWorkCorrectly() {
			assertThat(BatchStatus.FAILED.isGreaterThan(BatchStatus.COMPLETED)).isTrue();
			assertThat(BatchStatus.COMPLETED.isGreaterThan(BatchStatus.FAILED)).isFalse();
		}

		@Test
		void isLessThanShouldWorkCorrectly() {
			assertThat(BatchStatus.COMPLETED.isLessThan(BatchStatus.FAILED)).isTrue();
			assertThat(BatchStatus.FAILED.isLessThan(BatchStatus.COMPLETED)).isFalse();
		}

		@Test
		void sameShouldBeNeitherGreaterNorLess() {
			assertThat(BatchStatus.STARTED.isGreaterThan(BatchStatus.STARTED)).isFalse();
			assertThat(BatchStatus.STARTED.isLessThan(BatchStatus.STARTED)).isFalse();
		}
	}

}
