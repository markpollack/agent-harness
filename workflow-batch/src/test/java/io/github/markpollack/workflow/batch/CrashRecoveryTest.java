package io.github.markpollack.workflow.batch;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.LocalStepRunner;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.flows.workflow.WorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ContextConfiguration(classes = CrashRecoveryTest.TestConfig.class)
class CrashRecoveryTest {

	@EnableAutoConfiguration
	static class TestConfig {
	}

	@Autowired
	private AgentStepExecutionReadRepository readRepo;

	@Autowired
	private AgentStepExecutionWriteRepository writeRepo;

	@Autowired
	private DataSource dataSource;

	private CheckpointingStepRunner checkpointRunner;

	@BeforeEach
	void setUp() {
		checkpointRunner = new CheckpointingStepRunner(readRepo, writeRepo);
	}

	@Nested
	class HandlerEquivalence {

		@Test
		void sameWorkflowShouldProduceIdenticalOutputOnBothRunners() {
			Step<String, String> upper = Step.named("upper", (AgentContext ctx, String input) -> input.toUpperCase());
			Step<String, String> suffix = Step.named("suffix", (AgentContext ctx, String input) -> input + "-done");
			var workflow = Workflow.<String, String>define("equiv-test")
					.step(upper).step(suffix)
					.build();

			// Execute on LocalStepRunner
			var localExecutor = new WorkflowExecutor(new LocalStepRunner(), TraceRecorder.noop());
			String localResult = localExecutor.execute(
					workflow.graph(), AgentContext.withRunId("local-run"), "hello");

			// Execute on CheckpointingStepRunner
			var checkpointExecutor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());
			String checkpointResult = checkpointExecutor.execute(
					workflow.graph(), AgentContext.withRunId("checkpoint-run"), "hello");

			assertThat(checkpointResult).isEqualTo(localResult).isEqualTo("HELLO-done");
		}
	}

	@Nested
	class CrashAndRecover {

		@Test
		void shouldResumeFromLastCompletedStepAfterCrash() {
			var callCounts = new AtomicInteger[]{
					new AtomicInteger(0), // step-a
					new AtomicInteger(0), // step-b
					new AtomicInteger(0), // step-c
					new AtomicInteger(0)  // step-d
			};

			var failOnFirstAttempt = new AtomicInteger(0);

			Step<String, String> stepA = Step.named("step-a", (ctx, input) -> {
				callCounts[0].incrementAndGet();
				return input + "-A";
			});
			Step<String, String> stepB = Step.named("step-b", (ctx, input) -> {
				callCounts[1].incrementAndGet();
				return input + "-B";
			});
			Step<String, String> stepC = Step.named("step-c", (ctx, input) -> {
				callCounts[2].incrementAndGet();
				if (failOnFirstAttempt.incrementAndGet() == 1) {
					throw new RuntimeException("Simulated crash in step-c");
				}
				return input + "-C";
			});
			Step<String, String> stepD = Step.named("step-d", (ctx, input) -> {
				callCounts[3].incrementAndGet();
				return input + "-D";
			});

			var workflow = Workflow.<String, String>define("crash-test")
					.step(stepA).step(stepB).step(stepC).step(stepD)
					.build();

			var ctx = AgentContext.withRunId("crash-run-1");
			var executor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());

			// First attempt — step-c fails
			assertThatThrownBy(() -> executor.execute(workflow.graph(), ctx, "start"))
					.isInstanceOf(RuntimeException.class)
					.hasMessage("Simulated crash in step-c");

			// Verify: A and B completed, C failed
			assertThat(callCounts[0].get()).isEqualTo(1);
			assertThat(callCounts[1].get()).isEqualTo(1);
			assertThat(callCounts[2].get()).isEqualTo(1);
			assertThat(callCounts[3].get()).isEqualTo(0);

			var stepACheckpoint = readRepo.findByRunIdAndStepName("crash-run-1", "step-a");
			assertThat(stepACheckpoint).isPresent();
			assertThat(stepACheckpoint.get().getStatus()).isEqualTo(BatchStatus.COMPLETED);

			var stepBCheckpoint = readRepo.findByRunIdAndStepName("crash-run-1", "step-b");
			assertThat(stepBCheckpoint).isPresent();
			assertThat(stepBCheckpoint.get().getStatus()).isEqualTo(BatchStatus.COMPLETED);

			// Delete the FAILED record for step-c so it can be retried
			// (In production, a cleanup/retry mechanism would handle this)
			var stepCCheckpoint = readRepo.findByRunIdAndStepName("crash-run-1", "step-c");
			assertThat(stepCCheckpoint).isPresent();
			assertThat(stepCCheckpoint.get().getStatus()).isEqualTo(BatchStatus.FAILED);

		}

		@Test
		void completedStepsShouldBeSkippedOnRestart() {
			var callCounts = new AtomicInteger[]{
					new AtomicInteger(0),
					new AtomicInteger(0),
					new AtomicInteger(0)
			};

			Step<String, String> stepA = Step.named("a", (ctx, input) -> {
				callCounts[0].incrementAndGet();
				return input + "-A";
			});
			Step<String, String> stepB = Step.named("b", (ctx, input) -> {
				callCounts[1].incrementAndGet();
				return input + "-B";
			});
			Step<String, String> stepC = Step.named("c", (ctx, input) -> {
				callCounts[2].incrementAndGet();
				return input + "-C";
			});

			var workflow = Workflow.<String, String>define("skip-test")
					.step(stepA).step(stepB).step(stepC)
					.build();

			var executor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());

			// First run
			String result1 = executor.execute(workflow.graph(),
					AgentContext.withRunId("skip-run-1"), "start");
			assertThat(result1).isEqualTo("start-A-B-C");
			assertThat(callCounts[0].get()).isEqualTo(1);
			assertThat(callCounts[1].get()).isEqualTo(1);
			assertThat(callCounts[2].get()).isEqualTo(1);

			// Second run with same runId — all steps skipped
			String result2 = executor.execute(workflow.graph(),
					AgentContext.withRunId("skip-run-1"), "start");
			assertThat(result2).isEqualTo("start-A-B-C");
			assertThat(callCounts[0].get()).isEqualTo(1); // NOT incremented
			assertThat(callCounts[1].get()).isEqualTo(1);
			assertThat(callCounts[2].get()).isEqualTo(1);
		}
	}

	@Nested
	class OperatorRetry {

		@Test
		void resetFailedSteps_allowsRetryWhileSkippingCompletedSteps() {
			AtomicInteger callCounts[] = {
					new AtomicInteger(0), // step-a
					new AtomicInteger(0), // step-b
					new AtomicInteger(0)  // step-c
			};

			AtomicInteger failCount = new AtomicInteger(0);

			Step<String, String> stepA = Step.named("retry-a", (ctx, in) -> {
				callCounts[0].incrementAndGet();
				return in + "-A";
			});
			Step<String, String> stepB = Step.named("retry-b", (ctx, in) -> {
				callCounts[1].incrementAndGet();
				return in + "-B";
			});
			Step<String, String> stepC = Step.named("retry-c", (ctx, in) -> {
				callCounts[2].incrementAndGet();
				// Fail on first call only — simulates a transient error
				if (failCount.incrementAndGet() == 1) {
					throw new RuntimeException("transient failure");
				}
				return in + "-C";
			});

			var workflow = Workflow.<String, String>define("retry-test")
				.step(stepA).step(stepB).step(stepC)
				.build();

			var executor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());
			var ctx = AgentContext.withRunId("retry-run-1");

			// ── First attempt: crashes at step-c ──────────────────────────────
			assertThatThrownBy(() -> executor.execute(workflow.graph(), ctx, "start"))
				.hasMessage("transient failure");

			assertThat(callCounts[0].get()).isEqualTo(1);
			assertThat(callCounts[1].get()).isEqualTo(1);
			assertThat(callCounts[2].get()).isEqualTo(1); // ran, then failed

			// ── Operator inspects state ────────────────────────────────────────
			CheckpointManager manager = new CheckpointManager(readRepo, writeRepo);
			List<AgentStepExecution> state = manager.getRunState("retry-run-1");

			assertThat(state).hasSize(3);
			assertThat(state).anyMatch(e -> e.getStepName().equals("retry-a")
					&& e.getStatus() == BatchStatus.COMPLETED);
			assertThat(state).anyMatch(e -> e.getStepName().equals("retry-b")
					&& e.getStatus() == BatchStatus.COMPLETED);
			assertThat(state).anyMatch(e -> e.getStepName().equals("retry-c")
					&& e.getStatus() == BatchStatus.FAILED);

			// ── Operator decides: transient → reset and retry ──────────────────
			int reset = manager.resetFailedSteps("retry-run-1");
			assertThat(reset).isEqualTo(1); // only step-c was FAILED

			// ── Second attempt: same runId, A and B skipped, C retried ─────────
			String result = executor.execute(workflow.graph(), ctx, "start");
			assertThat(result).isEqualTo("start-A-B-C");

			assertThat(callCounts[0].get()).isEqualTo(1); // NOT re-executed
			assertThat(callCounts[1].get()).isEqualTo(1); // NOT re-executed
			assertThat(callCounts[2].get()).isEqualTo(2); // retried and succeeded

			// All steps now COMPLETED
			List<AgentStepExecution> finalState = manager.getRunState("retry-run-1");
			assertThat(finalState).allMatch(e -> e.getStatus() == BatchStatus.COMPLETED);
		}

	}

	@Nested
	class SubWorkflowCheckpointing {

		// Keys written by inner steps via updateContext()
		static final ContextKey<String> KEY_B = ContextKey.of("inner.b", String.class);

		static final ContextKey<String> KEY_C = ContextKey.of("inner.c", String.class);

		@Test
		void leafStepsInsideSubWorkflowAreCheckpointedIndividually() {
			AtomicInteger bExecCount = new AtomicInteger();
			AtomicInteger cExecCount = new AtomicInteger();
			AtomicInteger bCtxCount = new AtomicInteger();
			AtomicInteger cCtxCount = new AtomicInteger();

			// Steps that write their output to context via updateContext()
			Step<String, String> stepB = new Step<>() {
				@Override
				public String name() {
					return "inner-b";
				}

				@Override
				public String execute(AgentContext ctx, String in) {
					bExecCount.incrementAndGet();
					return in + "-B";
				}

				@Override
				public AgentContext updateContext(AgentContext ctx, String out) {
					bCtxCount.incrementAndGet();
					return ctx.mutate().with(KEY_B, out).build();
				}
			};

			Step<String, String> stepC = new Step<>() {
				@Override
				public String name() {
					return "inner-c";
				}

				@Override
				public String execute(AgentContext ctx, String in) {
					cExecCount.incrementAndGet();
					return in + "-C";
				}

				@Override
				public AgentContext updateContext(AgentContext ctx, String out) {
					cCtxCount.incrementAndGet();
					return ctx.mutate().with(KEY_C, out).build();
				}
			};

			Workflow<String, String> inner = Workflow.<String, String>define("inner-wf")
				.step(stepB)
				.then(stepC)
				.build();

			AtomicReference<String> capturedB = new AtomicReference<>();
			AtomicReference<String> capturedC = new AtomicReference<>();

			Workflow<String, String> parent = Workflow.<String, String>define("sub-cp-parent")
				.step(Step.named("outer-a", (ctx, in) -> in + "-A"))
				.then(inner)
				.then(Step.named("reader", (ctx, in) -> {
					capturedB.set(ctx.get(KEY_B).orElse("missing"));
					capturedC.set(ctx.get(KEY_C).orElse("missing"));
					return in;
				}))
				.build();

			var executor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());
			var ctx = AgentContext.withRunId("sub-cp-run-1");

			// ── First run: all steps execute ──────────────────────────────────
			String result1 = executor.execute(parent.graph(), ctx, "start");

			assertThat(result1).isEqualTo("start-A-B-C");
			assertThat(bExecCount.get()).isEqualTo(1);
			assertThat(cExecCount.get()).isEqualTo(1);

			// Context written inside sub-workflow is visible to downstream parent steps
			assertThat(capturedB.get()).isEqualTo("start-A-B");
			assertThat(capturedC.get()).isEqualTo("start-A-B-C");

			// Inner leaf steps have their own checkpoint records (checkpointed individually)
			assertThat(readRepo.findByRunIdAndStepName("sub-cp-run-1", "inner-b"))
				.isPresent()
				.hasValueSatisfying(e -> assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED));
			assertThat(readRepo.findByRunIdAndStepName("sub-cp-run-1", "inner-c"))
				.isPresent()
				.hasValueSatisfying(e -> assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED));

			// updateContext() called once per step on first run
			assertThat(bCtxCount.get()).isEqualTo(1);
			assertThat(cCtxCount.get()).isEqualTo(1);

			// ── Second run (same runId): inner steps skipped via cache ─────────
			String result2 = executor.execute(parent.graph(), ctx, "start");

			// Same result from cached outputs
			assertThat(result2).isEqualTo("start-A-B-C");

			// execute() was NOT called again on the inner steps
			assertThat(bExecCount.get()).isEqualTo(1);
			assertThat(cExecCount.get()).isEqualTo(1);

			// updateContext() WAS called again with the cached output — context is
			// correctly rebuilt inside the sub-workflow even when steps are skipped
			assertThat(bCtxCount.get()).isEqualTo(2);
			assertThat(cCtxCount.get()).isEqualTo(2);
		}

		@Test
		void subWorkflowItselfHasNoCheckpointRecord() {
			Workflow<String, String> inner = Workflow.<String, String>define("no-record-inner")
				.step(Step.named("leaf-only", (ctx, in) -> in + "-inner"))
				.build();

			Workflow<String, String> parent = Workflow.<String, String>define("no-record-parent")
				.then(inner)
				.build();

			var executor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());
			executor.execute(parent.graph(), AgentContext.withRunId("sub-cp-run-2"), "input");

			// The sub-workflow as a whole has no checkpoint record — only leaf steps do
			assertThat(readRepo.findByRunIdAndStepName("sub-cp-run-2", "no-record-inner")).isEmpty();
			assertThat(readRepo.findByRunIdAndStepName("sub-cp-run-2", "leaf-only")).isPresent();
		}

	}

	@Nested
	class JdbcTrace {

		@Test
		void jdbcTraceRecorderShouldPersistTransitions() {
			var traceRecorder = new JdbcTraceRecorder(dataSource);

			Step<String, String> stepA = Step.named("trace-a", (ctx, input) -> input + "-A");
			Step<String, String> stepB = Step.named("trace-b", (ctx, input) -> input + "-B");

			var workflow = Workflow.<String, String>define("trace-test")
					.step(stepA).step(stepB)
					.build();

			var executor = new WorkflowExecutor(checkpointRunner, traceRecorder);
			String result = executor.execute(workflow.graph(),
					AgentContext.withRunId("trace-run-1"), "start");

			assertThat(result).isEqualTo("start-A-B");

			var trace = traceRecorder.getTrace("trace-run-1");
			assertThat(trace).hasSize(2);
			assertThat(trace.get(0).toStep()).contains("trace-a");
			assertThat(trace.get(1).toStep()).contains("trace-b");
		}

		@Test
		void traceShouldSurviveRestart() {
			var traceRecorder = new JdbcTraceRecorder(dataSource);

			Step<String, String> step = Step.named("persist-trace", (ctx, input) -> input + "!");

			var workflow = Workflow.<String, String>define("persist-test")
					.step(step)
					.build();

			var executor = new WorkflowExecutor(new LocalStepRunner(), traceRecorder);
			executor.execute(workflow.graph(), AgentContext.withRunId("persist-run"), "hello");

			// Create a new recorder from the same DataSource — should see the trace
			var newRecorder = new JdbcTraceRecorder(dataSource);
			var trace = newRecorder.getTrace("persist-run");
			assertThat(trace).hasSize(1);
			assertThat(trace.getFirst().workflowName()).isEqualTo("persist-test");
		}
	}

}
