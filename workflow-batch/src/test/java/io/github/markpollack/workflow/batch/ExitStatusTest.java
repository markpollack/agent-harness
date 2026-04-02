package io.github.markpollack.workflow.batch;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExitStatusTest {

	@Nested
	class Construction {

		@Test
		void singleArgConstructorShouldUseEmptyDescription() {
			var status = new ExitStatus("MY_CODE");
			assertThat(status.exitCode()).isEqualTo("MY_CODE");
			assertThat(status.exitDescription()).isEmpty();
		}

		@Test
		void nullDescriptionShouldBeNormalized() {
			var status = new ExitStatus("CODE", null);
			assertThat(status.exitDescription()).isEmpty();
		}

		@Test
		void twoArgConstructorShouldPreserveBoth() {
			var status = new ExitStatus("CODE", "some description");
			assertThat(status.exitCode()).isEqualTo("CODE");
			assertThat(status.exitDescription()).isEqualTo("some description");
		}
	}

	@Nested
	class StandardConstants {

		@Test
		void standardConstantsShouldHaveCorrectCodes() {
			assertThat(ExitStatus.UNKNOWN.exitCode()).isEqualTo("UNKNOWN");
			assertThat(ExitStatus.EXECUTING.exitCode()).isEqualTo("EXECUTING");
			assertThat(ExitStatus.COMPLETED.exitCode()).isEqualTo("COMPLETED");
			assertThat(ExitStatus.NOOP.exitCode()).isEqualTo("NOOP");
			assertThat(ExitStatus.FAILED.exitCode()).isEqualTo("FAILED");
			assertThat(ExitStatus.STOPPED.exitCode()).isEqualTo("STOPPED");
		}

		@Test
		void standardConstantsShouldHaveEmptyDescriptions() {
			assertThat(ExitStatus.COMPLETED.exitDescription()).isEmpty();
			assertThat(ExitStatus.FAILED.exitDescription()).isEmpty();
		}
	}

	@Nested
	class SeverityRanking {

		@Test
		void executingShouldBeLowerThanCompleted() {
			assertThat(ExitStatus.EXECUTING.compareTo(ExitStatus.COMPLETED)).isLessThan(0);
		}

		@Test
		void completedShouldBeLowerThanFailed() {
			assertThat(ExitStatus.COMPLETED.compareTo(ExitStatus.FAILED)).isLessThan(0);
		}

		@Test
		void failedShouldBeLowerThanUnknown() {
			assertThat(ExitStatus.FAILED.compareTo(ExitStatus.UNKNOWN)).isLessThan(0);
		}

		@Test
		void fullSeverityOrderShouldBeCorrect() {
			// EXECUTING(1) < COMPLETED(2) < NOOP(3) < STOPPED(4) < FAILED(5) < UNKNOWN(6)
			assertThat(ExitStatus.EXECUTING).isLessThan(ExitStatus.COMPLETED);
			assertThat(ExitStatus.COMPLETED).isLessThan(ExitStatus.NOOP);
			assertThat(ExitStatus.NOOP).isLessThan(ExitStatus.STOPPED);
			assertThat(ExitStatus.STOPPED).isLessThan(ExitStatus.FAILED);
			assertThat(ExitStatus.FAILED).isLessThan(ExitStatus.UNKNOWN);
		}

		@Test
		void customCodeShouldBeHighestSeverity() {
			var custom = new ExitStatus("CUSTOM_STATUS");
			assertThat(custom.compareTo(ExitStatus.UNKNOWN)).isGreaterThan(0);
		}

		@Test
		void codeStartingWithKnownPrefixShouldMatchThatSeverity() {
			// "FAILED_RATE_LIMIT" starts with "FAILED" → severity 5, same as FAILED
			// Alphabetically FAILED < FAILED_RATE_LIMIT, so it sorts slightly higher
			var rateLimited = new ExitStatus("FAILED_RATE_LIMIT");
			assertThat(rateLimited.compareTo(ExitStatus.FAILED)).isGreaterThan(0);
		}
	}

	@Nested
	class AndCombinator {

		@Test
		void andWithNullShouldReturnThis() {
			assertThat(ExitStatus.COMPLETED.and(null)).isEqualTo(ExitStatus.COMPLETED);
		}

		@Test
		void andShouldTakeHigherSeverityCode() {
			var result = ExitStatus.COMPLETED.and(ExitStatus.FAILED);
			assertThat(result.exitCode()).isEqualTo("FAILED");
		}

		@Test
		void andShouldConcatenateDescriptions() {
			var a = new ExitStatus("COMPLETED", "step A done");
			var b = new ExitStatus("COMPLETED", "step B done");
			var result = a.and(b);
			assertThat(result.exitDescription()).isEqualTo("step A done; step B done");
		}

		@Test
		void andShouldNotDuplicateIdenticalDescriptions() {
			var a = new ExitStatus("COMPLETED", "same desc");
			var b = new ExitStatus("COMPLETED", "same desc");
			var result = a.and(b);
			assertThat(result.exitDescription()).isEqualTo("same desc");
		}

		@Test
		void andWithEmptyDescriptionsShouldProduceEmptyDescription() {
			var result = ExitStatus.COMPLETED.and(ExitStatus.FAILED);
			assertThat(result.exitDescription()).isEmpty();
		}
	}

	@Nested
	class AddExitDescription {

		@Test
		void shouldAppendToExistingDescription() {
			var status = new ExitStatus("COMPLETED", "first");
			var result = status.addExitDescription("second");
			assertThat(result.exitDescription()).isEqualTo("first; second");
		}

		@Test
		void shouldSetDescriptionWhenEmpty() {
			var result = ExitStatus.COMPLETED.addExitDescription("new description");
			assertThat(result.exitDescription()).isEqualTo("new description");
		}

		@Test
		void shouldNotAppendBlankDescription() {
			var status = new ExitStatus("COMPLETED", "existing");
			var result = status.addExitDescription("   ");
			assertThat(result.exitDescription()).isEqualTo("existing");
		}

		@Test
		void shouldAppendThrowableStackTrace() {
			var ex = new RuntimeException("boom");
			var result = ExitStatus.FAILED.addExitDescription(ex);
			assertThat(result.exitDescription()).contains("java.lang.RuntimeException: boom");
		}
	}

	@Nested
	class ReplaceExitCode {

		@Test
		void shouldReplaceCodePreservingDescription() {
			var status = new ExitStatus("COMPLETED", "all done");
			var result = status.replaceExitCode("FAILED");
			assertThat(result.exitCode()).isEqualTo("FAILED");
			assertThat(result.exitDescription()).isEqualTo("all done");
		}
	}

}
