package io.github.markpollack.workflow.batch;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.LocalStepRunner;
import io.github.markpollack.workflow.flows.workflow.StepTransition;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.flows.workflow.WorkflowExecutor;
import io.github.markpollack.workflow.patterns.graph.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end durability integration tests verifying database persistence behavior.
 *
 * <p>Tests follow JPA testing best practices:
 * <ul>
 *   <li>Use {@link TestEntityManager} for setup (not the repository under test)</li>
 *   <li>Always {@code flush()} + {@code clear()} before read assertions</li>
 *   <li>Verify ALL fields on persistence round-trips</li>
 *   <li>Test full workflow crash-and-resume lifecycle</li>
 * </ul>
 */
@DataJpaTest
@ContextConfiguration(classes = DurabilityIntegrationTest.TestConfig.class)
class DurabilityIntegrationTest {

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

	@Autowired
	private DataSource dataSource;

	private CheckpointingStepRunner checkpointRunner;

	@BeforeEach
	void setUp() {
		checkpointRunner = new CheckpointingStepRunner(readRepo, writeRepo);
	}

	// ─── Entity persistence round-trips ──────────────────────────────────

	@Nested
	class EntityRoundTrip {

		@Test
		void stepExecutionShouldSurvivePersistenceRoundTripWithAllFields() {
			var step = new AgentStepExecution("run-rt-1", "classify");
			step.upgradeStatus(BatchStatus.STARTED);
			step.setStartTime(Instant.parse("2026-04-01T10:00:00Z"));
			step.setEndTime(Instant.parse("2026-04-01T10:01:30Z"));
			step.setExitStatus(ExitStatus.COMPLETED);
			step.setTokensUsed(2500L);
			step.setToolCallCount(12);
			step.setOutputPayload("{\"category\":\"technical\"}");
			step.setCostUsd(0.042);
			step.setErrorMessage(null);

			em.persistAndFlush(step);
			em.clear(); // Evict L1 cache — force reload from DB

			var found = readRepo.findByRunIdAndStepName("run-rt-1", "classify").orElseThrow();

			assertThat(found.getId()).isNotNull();
			assertThat(found.getVersion()).isNotNull();
			assertThat(found.getRunId()).isEqualTo("run-rt-1");
			assertThat(found.getStepName()).isEqualTo("classify");
			assertThat(found.getStatus()).isEqualTo(BatchStatus.STARTED);
			assertThat(found.getExitStatus().exitCode()).isEqualTo("COMPLETED");
			assertThat(found.getExitStatus().exitDescription()).isEmpty();
			assertThat(found.getTokensUsed()).isEqualTo(2500L);
			assertThat(found.getToolCallCount()).isEqualTo(12);
			assertThat(found.getOutputPayload()).isEqualTo("{\"category\":\"technical\"}");
			assertThat(found.getCostUsd()).isEqualTo(0.042);
			assertThat(found.getStartTime()).isCloseTo(
					Instant.parse("2026-04-01T10:00:00Z"), within(1, ChronoUnit.MILLIS));
			assertThat(found.getEndTime()).isCloseTo(
					Instant.parse("2026-04-01T10:01:30Z"), within(1, ChronoUnit.MILLIS));
			assertThat(found.getCreateTime()).isNotNull();
			assertThat(found.getLastUpdated()).isNotNull();
			assertThat(found.getErrorMessage()).isNull();
		}

		@Test
		void flowExecutionShouldSurvivePersistenceRoundTripWithAllFields() {
			var flow = new AgentFlowExecution("run-rt-2", "multi-agent-pipeline");
			flow.upgradeStatus(BatchStatus.STARTED);
			flow.setStartTime(Instant.parse("2026-04-01T09:00:00Z"));
			flow.setEndTime(Instant.parse("2026-04-01T09:15:00Z"));
			flow.setExitStatus(ExitStatus.COMPLETED);
			flow.setStepsTotal(5);
			flow.setStepsCompleted(5);
			flow.setTotalCostUsd(1.23);

			em.persistAndFlush(flow);
			em.clear();

			var found = flowRepo.findByRunId("run-rt-2").orElseThrow();

			assertThat(found.getId()).isNotNull();
			assertThat(found.getVersion()).isNotNull();
			assertThat(found.getRunId()).isEqualTo("run-rt-2");
			assertThat(found.getWorkflowName()).isEqualTo("multi-agent-pipeline");
			assertThat(found.getStatus()).isEqualTo(BatchStatus.STARTED);
			assertThat(found.getExitStatus().exitCode()).isEqualTo("COMPLETED");
			assertThat(found.getStepsTotal()).isEqualTo(5);
			assertThat(found.getStepsCompleted()).isEqualTo(5);
			assertThat(found.getTotalCostUsd()).isEqualTo(1.23);
			assertThat(found.getStartTime()).isCloseTo(
					Instant.parse("2026-04-01T09:00:00Z"), within(1, ChronoUnit.MILLIS));
			assertThat(found.getEndTime()).isCloseTo(
					Instant.parse("2026-04-01T09:15:00Z"), within(1, ChronoUnit.MILLIS));
			assertThat(found.getCreateTime()).isNotNull();
			assertThat(found.getLastUpdated()).isNotNull();
		}

		@Test
		void exitStatusWithCustomCodeAndLongDescriptionShouldSurviveRoundTrip() {
			var step = new AgentStepExecution("run-rt-3", "llm-call");
			var exitStatus = new ExitStatus("FAILED_RATE_LIMIT",
					"Rate limit exceeded. Retry after 30 seconds. " +
							"Request ID: req_abc123. Model: claude-opus-4-6. " +
							"Tokens requested: 100000.");
			step.setExitStatus(exitStatus);

			em.persistAndFlush(step);
			em.clear();

			var found = readRepo.findByRunIdAndStepName("run-rt-3", "llm-call").orElseThrow();

			assertThat(found.getExitStatus().exitCode()).isEqualTo("FAILED_RATE_LIMIT");
			assertThat(found.getExitStatus().exitDescription()).contains("Rate limit exceeded");
			assertThat(found.getExitStatus().exitDescription()).contains("req_abc123");
		}

		@Test
		void nullOptionalFieldsShouldRoundTripCorrectly() {
			var step = new AgentStepExecution("run-rt-4", "optional-fields");
			// startTime, endTime, outputPayload, errorMessage are all null by default

			em.persistAndFlush(step);
			em.clear();

			var found = readRepo.findByRunIdAndStepName("run-rt-4", "optional-fields").orElseThrow();

			assertThat(found.getStartTime()).isNull();
			assertThat(found.getEndTime()).isNull();
			assertThat(found.getOutputPayload()).isNull();
			assertThat(found.getErrorMessage()).isNull();
			// Non-null fields should still be populated
			assertThat(found.getCreateTime()).isNotNull();
			assertThat(found.getStatus()).isEqualTo(BatchStatus.STARTING);
			assertThat(found.getTokensUsed()).isZero();
			assertThat(found.getCostUsd()).isZero();
		}
	}

	// ─── @Modifying bulk update round-trips ──────────────────────────────

	@Nested
	class BulkUpdateRoundTrip {

		@Test
		void updateCompletedShouldPersistAllFieldsAndIncrementVersion() {
			var step = new AgentStepExecution("run-upd-1", "summarize");
			step.upgradeStatus(BatchStatus.STARTED);
			step.setStartTime(Instant.now());
			em.persistAndFlush(step);
			int initialVersion = step.getVersion();
			em.clear();

			var endTime = Instant.now();
			writeRepo.updateCompleted(
					step.getId(),
					BatchStatus.COMPLETED,
					new ExitStatus("COMPLETED", "Summarized 3 documents"),
					String.class.getName(),
					"This is the summary output text",
					3200L, 15, 0.058,
					endTime, endTime);
			em.clear();

			var found = readRepo.findByRunIdAndStepName("run-upd-1", "summarize").orElseThrow();
			assertThat(found.getStatus()).isEqualTo(BatchStatus.COMPLETED);
			assertThat(found.getExitStatus().exitCode()).isEqualTo("COMPLETED");
			assertThat(found.getExitStatus().exitDescription()).isEqualTo("Summarized 3 documents");
			assertThat(found.getOutputPayload()).isEqualTo("This is the summary output text");
			assertThat(found.getTokensUsed()).isEqualTo(3200L);
			assertThat(found.getToolCallCount()).isEqualTo(15);
			assertThat(found.getCostUsd()).isEqualTo(0.058);
			assertThat(found.getEndTime()).isCloseTo(endTime, within(1, ChronoUnit.MILLIS));
			assertThat(found.getVersion()).isEqualTo(initialVersion + 1);
		}

		@Test
		void updateFailedShouldPersistErrorDetailsAndIncrementVersion() {
			var step = new AgentStepExecution("run-upd-2", "agent-call");
			step.upgradeStatus(BatchStatus.STARTED);
			step.setStartTime(Instant.now());
			em.persistAndFlush(step);
			int initialVersion = step.getVersion();
			em.clear();

			var endTime = Instant.now();
			var failedExit = ExitStatus.FAILED.addExitDescription(
					"java.net.SocketTimeoutException: Connect timed out after 30000ms");
			writeRepo.updateFailed(
					step.getId(),
					BatchStatus.FAILED,
					failedExit,
					"Connect timed out after 30000ms",
					endTime, endTime);
			em.clear();

			var found = readRepo.findByRunIdAndStepName("run-upd-2", "agent-call").orElseThrow();
			assertThat(found.getStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(found.getExitStatus().exitCode()).isEqualTo("FAILED");
			assertThat(found.getExitStatus().exitDescription()).contains("SocketTimeoutException");
			assertThat(found.getErrorMessage()).isEqualTo("Connect timed out after 30000ms");
			assertThat(found.getVersion()).isEqualTo(initialVersion + 1);
		}
	}

	// ─── Full workflow crash-and-resume ──────────────────────────────────

	@Nested
	class WorkflowCrashAndResume {

		@Test
		void shouldCrashAtStepThreeAndResumeFromCheckpoint() {
			// Track how many times each step actually executes
			var callCounts = new AtomicInteger[]{
					new AtomicInteger(0), // step-1
					new AtomicInteger(0), // step-2
					new AtomicInteger(0), // step-3
					new AtomicInteger(0), // step-4
					new AtomicInteger(0)  // step-5
			};

			var crashFlag = new AtomicInteger(0);

			Step<String, String> step1 = Step.named("step-1", (AgentContext ctx, String in) -> {
				callCounts[0].incrementAndGet();
				return in + "→1";
			});
			Step<String, String> step2 = Step.named("step-2", (AgentContext ctx, String in) -> {
				callCounts[1].incrementAndGet();
				return in + "→2";
			});
			Step<String, String> step3 = Step.named("step-3", (AgentContext ctx, String in) -> {
				callCounts[2].incrementAndGet();
				if (crashFlag.incrementAndGet() == 1) {
					throw new RuntimeException("Simulated JVM crash in step-3");
				}
				return in + "→3";
			});
			Step<String, String> step4 = Step.named("step-4", (AgentContext ctx, String in) -> {
				callCounts[3].incrementAndGet();
				return in + "→4";
			});
			Step<String, String> step5 = Step.named("step-5", (AgentContext ctx, String in) -> {
				callCounts[4].incrementAndGet();
				return in + "→5";
			});

			var workflow = Workflow.<String, String>define("crash-resume-test")
					.step(step1).step(step2).step(step3).step(step4).step(step5)
					.build();

			String runId = "crash-resume-run-1";
			var ctx = AgentContext.withRunId(runId);
			var executor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());

			// ── FIRST ATTEMPT: crash at step-3 ──
			assertThatThrownBy(() -> executor.execute(workflow.graph(), ctx, "start"))
					.isInstanceOf(RuntimeException.class)
					.hasMessage("Simulated JVM crash in step-3");

			// Verify call counts: steps 1-2 executed, step-3 started but crashed
			assertThat(callCounts[0].get()).isEqualTo(1);
			assertThat(callCounts[1].get()).isEqualTo(1);
			assertThat(callCounts[2].get()).isEqualTo(1);
			assertThat(callCounts[3].get()).isEqualTo(0); // never reached
			assertThat(callCounts[4].get()).isEqualTo(0);

			// Verify DB state: steps 1-2 COMPLETED, step-3 FAILED
			var s1 = readRepo.findByRunIdAndStepName(runId, "step-1").orElseThrow();
			assertThat(s1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
			assertThat(s1.getOutputPayload()).isEqualTo("start→1");

			var s2 = readRepo.findByRunIdAndStepName(runId, "step-2").orElseThrow();
			assertThat(s2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
			assertThat(s2.getOutputPayload()).isEqualTo("start→1→2");

			var s3 = readRepo.findByRunIdAndStepName(runId, "step-3").orElseThrow();
			assertThat(s3.getStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(s3.getErrorMessage()).isEqualTo("Simulated JVM crash in step-3");

			// Steps 4-5 have no checkpoint at all
			assertThat(readRepo.findByRunIdAndStepName(runId, "step-4")).isEmpty();
			assertThat(readRepo.findByRunIdAndStepName(runId, "step-5")).isEmpty();

			// ── CLEAN UP FAILED RECORD (simulate retry preparation) ──
			writeRepo.deleteById(s3.getId());
			em.flush();
			em.clear();

			// ── SECOND ATTEMPT: resume with same runId ──
			// Steps 1-2 should be SKIPPED (cached output returned).
			// Steps 3-5 should EXECUTE.
			String result = executor.execute(workflow.graph(), ctx, "start");

			assertThat(result).isEqualTo("start→1→2→3→4→5");

			// Verify call counts: steps 1-2 NOT re-executed
			assertThat(callCounts[0].get()).isEqualTo(1); // still 1 — skipped
			assertThat(callCounts[1].get()).isEqualTo(1); // still 1 — skipped
			assertThat(callCounts[2].get()).isEqualTo(2); // re-executed (attempt 2)
			assertThat(callCounts[3].get()).isEqualTo(1); // first execution
			assertThat(callCounts[4].get()).isEqualTo(1); // first execution

			// Verify all 5 steps are now COMPLETED in DB
			List<AgentStepExecution> allSteps = readRepo.findByRunId(runId);
			assertThat(allSteps).hasSize(5);
			assertThat(allSteps).allMatch(s -> s.getStatus() == BatchStatus.COMPLETED);
		}

		@Test
		void fullyCompletedWorkflowShouldSkipAllStepsOnRerun() {
			var callCount = new AtomicInteger(0);

			Step<String, String> a = Step.named("a", (AgentContext ctx, String in) -> {
				callCount.incrementAndGet();
				return in + "-A";
			});
			Step<String, String> b = Step.named("b", (AgentContext ctx, String in) -> {
				callCount.incrementAndGet();
				return in + "-B";
			});
			Step<String, String> c = Step.named("c", (AgentContext ctx, String in) -> {
				callCount.incrementAndGet();
				return in + "-C";
			});

			var workflow = Workflow.<String, String>define("idempotent-test")
					.step(a).step(b).step(c)
					.build();

			var ctx = AgentContext.withRunId("idempotent-run-1");
			var executor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());

			// First run — all steps execute
			String result1 = executor.execute(workflow.graph(), ctx, "go");
			assertThat(result1).isEqualTo("go-A-B-C");
			assertThat(callCount.get()).isEqualTo(3);

			// Second run, same runId — all steps skipped from checkpoint
			String result2 = executor.execute(workflow.graph(), ctx, "go");
			assertThat(result2).isEqualTo("go-A-B-C"); // same output
			assertThat(callCount.get()).isEqualTo(3);   // no additional calls

			// Third run — still skipped
			String result3 = executor.execute(workflow.graph(), ctx, "go");
			assertThat(result3).isEqualTo("go-A-B-C");
			assertThat(callCount.get()).isEqualTo(3);
		}

		@Test
		void differentRunIdShouldExecuteAllStepsIndependently() {
			var callCount = new AtomicInteger(0);

			Step<String, String> step = Step.named("counter", (AgentContext ctx, String in) -> {
				callCount.incrementAndGet();
				return in + "-counted";
			});

			var workflow = Workflow.<String, String>define("isolation-test")
					.step(step)
					.build();

			var executor = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop());

			// Run 1
			executor.execute(workflow.graph(), AgentContext.withRunId("run-A"), "x");
			assertThat(callCount.get()).isEqualTo(1);

			// Run 2 — different runId, should execute independently
			executor.execute(workflow.graph(), AgentContext.withRunId("run-B"), "x");
			assertThat(callCount.get()).isEqualTo(2);

			// Re-run 1 — same runId, should skip
			executor.execute(workflow.graph(), AgentContext.withRunId("run-A"), "x");
			assertThat(callCount.get()).isEqualTo(2); // unchanged
		}
	}

	// ─── CheckpointingStepRunner + WorkflowExecutor equivalence ──────────

	@Nested
	class StepRunnerEquivalence {

		@Test
		void checkpointRunnerShouldProduceIdenticalOutputToLocalRunner() {
			Step<String, String> upper = Step.named("upper",
					(AgentContext ctx, String in) -> in.toUpperCase());
			Step<String, String> trim = Step.named("trim",
					(AgentContext ctx, String in) -> in.strip());
			Step<String, String> prefix = Step.named("prefix",
					(AgentContext ctx, String in) -> "RESULT: " + in);

			var workflow = Workflow.<String, String>define("equivalence-test")
					.step(upper).step(trim).step(prefix)
					.build();

			// Local (no persistence)
			var localResult = new WorkflowExecutor(new LocalStepRunner(), TraceRecorder.noop())
					.execute(workflow.graph(), AgentContext.withRunId("local-1"), "  hello world  ");

			// Checkpointed (JDBC persistence)
			var checkpointResult = new WorkflowExecutor(checkpointRunner, TraceRecorder.noop())
					.execute(workflow.graph(), AgentContext.withRunId("checkpoint-1"), "  hello world  ");

			assertThat(checkpointResult)
					.isEqualTo(localResult)
					.isEqualTo("RESULT: HELLO WORLD");

			// Verify checkpoints were persisted
			var steps = readRepo.findByRunId("checkpoint-1");
			assertThat(steps).hasSize(3);
			assertThat(steps).allMatch(s -> s.getStatus() == BatchStatus.COMPLETED);
		}
	}

	// ─── JdbcTraceRecorder field fidelity ────────────────────────────────

	@Nested
	class JdbcTraceRecorderFidelity {

		@Test
		void allStepTransitionFieldsShouldSurviveJdbcRoundTrip() {
			var recorder = new JdbcTraceRecorder(dataSource);

			var transition = new StepTransition(
					"fidelity-run-1",
					"my-workflow",
					"step-a",
					"step-b",
					Instant.parse("2026-04-01T12:00:00Z"),
					Duration.ofMillis(1500),
					4200L,
					0.075,
					NodeType.DETERMINISTIC,
					"route-alpha");

			recorder.record(transition);

			List<StepTransition> trace = recorder.getTrace("fidelity-run-1");
			assertThat(trace).hasSize(1);

			StepTransition loaded = trace.getFirst();
			assertThat(loaded.workflowRunId()).isEqualTo("fidelity-run-1");
			assertThat(loaded.workflowName()).isEqualTo("my-workflow");
			assertThat(loaded.fromStep()).isEqualTo("step-a");
			assertThat(loaded.toStep()).isEqualTo("step-b");
			assertThat(loaded.timestamp()).isCloseTo(
					Instant.parse("2026-04-01T12:00:00Z"), within(1, ChronoUnit.SECONDS));
			assertThat(loaded.stepDuration()).isEqualTo(Duration.ofMillis(1500));
			assertThat(loaded.tokensUsed()).isEqualTo(4200L);
			assertThat(loaded.costUsd()).isEqualTo(0.075);
			assertThat(loaded.nodeType()).isEqualTo(NodeType.DETERMINISTIC);
			assertThat(loaded.label()).isEqualTo("route-alpha");
		}

		@Test
		void nullFromStepAndNullLabelShouldRoundTrip() {
			var recorder = new JdbcTraceRecorder(dataSource);

			var transition = new StepTransition(
					"fidelity-run-2",
					"simple-wf",
					null, // first step has no predecessor
					"entry-step",
					Instant.now(),
					Duration.ofMillis(50),
					0L,
					0.0,
					NodeType.DETERMINISTIC,
					null); // sequential step has no label

			recorder.record(transition);

			StepTransition loaded = recorder.getTrace("fidelity-run-2").getFirst();
			assertThat(loaded.fromStep()).isNull();
			assertThat(loaded.label()).isNull();
		}

		@Test
		void multipleTransitionsShouldBeOrderedByInsertionSequence() {
			var recorder = new JdbcTraceRecorder(dataSource);
			String runId = "fidelity-run-3";

			recorder.record(new StepTransition(runId, "wf", null, "step-1",
					Instant.now(), Duration.ofMillis(100), 0, 0.0, NodeType.DETERMINISTIC));
			recorder.record(new StepTransition(runId, "wf", "step-1", "step-2",
					Instant.now(), Duration.ofMillis(200), 1000, 0.01, NodeType.AGENT));
			recorder.record(new StepTransition(runId, "wf", "step-2", "step-3",
					Instant.now(), Duration.ofMillis(300), 2000, 0.02, NodeType.AGENT));

			List<StepTransition> trace = recorder.getTrace(runId);
			assertThat(trace).hasSize(3);
			assertThat(trace).extracting(StepTransition::toStep)
					.containsExactly("step-1", "step-2", "step-3");
			assertThat(trace).extracting(StepTransition::nodeType)
					.containsExactly(NodeType.DETERMINISTIC, NodeType.AGENT, NodeType.AGENT);
		}

		@Test
		void getTraceShouldReturnEmptyListForUnknownRun() {
			var recorder = new JdbcTraceRecorder(dataSource);
			assertThat(recorder.getTrace("nonexistent-run")).isEmpty();
		}
	}

	// ─── Combined: checkpoint + trace in same workflow ───────────────────

	@Nested
	class CheckpointPlusTrace {

		@Test
		void workflowShouldPersistBothCheckpointsAndTraceRecords() {
			var traceRecorder = new JdbcTraceRecorder(dataSource);

			Step<String, String> analyze = Step.named("analyze",
					(AgentContext ctx, String in) -> "analyzed: " + in);
			Step<String, String> summarize = Step.named("summarize",
					(AgentContext ctx, String in) -> "summary of [" + in + "]");

			var workflow = Workflow.<String, String>define("full-durability-test")
					.step(analyze).step(summarize)
					.build();

			var ctx = AgentContext.withRunId("full-durable-run-1");
			var executor = new WorkflowExecutor(checkpointRunner, traceRecorder);

			String result = executor.execute(workflow.graph(), ctx, "raw data");
			assertThat(result).isEqualTo("summary of [analyzed: raw data]");

			// Verify checkpoints
			var checkpoints = readRepo.findByRunId("full-durable-run-1");
			assertThat(checkpoints).hasSize(2);
			assertThat(checkpoints).extracting(AgentStepExecution::getStepName)
					.containsExactlyInAnyOrder("analyze", "summarize");
			assertThat(checkpoints).allMatch(s -> s.getStatus() == BatchStatus.COMPLETED);

			// Verify trace — node names may have suffixes (e.g., "analyze-1")
			var trace = traceRecorder.getTrace("full-durable-run-1");
			assertThat(trace).hasSizeGreaterThanOrEqualTo(2);
			assertThat(trace).extracting(StepTransition::toStep)
					.anyMatch(name -> name.contains("analyze"))
					.anyMatch(name -> name.contains("summarize"));
		}

		@Test
		void rerunningSameWorkflowShouldSkipStepsButStillProduceTraceOnFirstRun() {
			var traceRecorder = new JdbcTraceRecorder(dataSource);
			var callCount = new AtomicInteger(0);

			Step<String, String> step = Step.named("tracked",
					(AgentContext ctx, String in) -> {
						callCount.incrementAndGet();
						return "done";
					});

			var workflow = Workflow.<String, String>define("rerun-trace-test")
					.step(step)
					.build();

			var ctx = AgentContext.withRunId("rerun-trace-run-1");
			var executor = new WorkflowExecutor(checkpointRunner, traceRecorder);

			// First run — executes and records trace
			executor.execute(workflow.graph(), ctx, "input");
			assertThat(callCount.get()).isEqualTo(1);
			assertThat(traceRecorder.getTrace("rerun-trace-run-1")).isNotEmpty();

			// Second run — step skipped from checkpoint
			executor.execute(workflow.graph(), ctx, "input");
			assertThat(callCount.get()).isEqualTo(1); // unchanged
		}
	}

	// ─── FlowExecution lifecycle ─────────────────────────────────────────

	@Nested
	class FlowExecutionLifecycle {

		@Test
		void flowShouldTrackProgressThroughFullLifecycle() {
			// Simulate a workflow manager tracking flow progress
			var flow = new AgentFlowExecution("lifecycle-run-1", "data-pipeline");
			flow.upgradeStatus(BatchStatus.STARTED);
			flow.setStartTime(Instant.now());
			flow.setStepsTotal(4);
			flowRepo.saveAndFlush(flow);
			em.clear();

			// Simulate steps completing
			var loaded = flowRepo.findByRunId("lifecycle-run-1").orElseThrow();
			assertThat(loaded.getStatus()).isEqualTo(BatchStatus.STARTED);
			assertThat(loaded.getStepsCompleted()).isZero();

			// Update progress
			loaded.setStepsCompleted(2);
			loaded.setTotalCostUsd(0.15);
			flowRepo.saveAndFlush(loaded);
			em.clear();

			var midway = flowRepo.findByRunId("lifecycle-run-1").orElseThrow();
			assertThat(midway.getStepsCompleted()).isEqualTo(2);
			assertThat(midway.getTotalCostUsd()).isEqualTo(0.15);

			// Complete
			midway.upgradeStatus(BatchStatus.COMPLETED);
			midway.setStepsCompleted(4);
			midway.setTotalCostUsd(0.45);
			midway.setEndTime(Instant.now());
			midway.setExitStatus(ExitStatus.COMPLETED);
			flowRepo.saveAndFlush(midway);
			em.clear();

			var completed = flowRepo.findByRunId("lifecycle-run-1").orElseThrow();
			assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
			assertThat(completed.getStepsCompleted()).isEqualTo(4);
			assertThat(completed.getTotalCostUsd()).isEqualTo(0.45);
			assertThat(completed.getEndTime()).isNotNull();
			assertThat(completed.getExitStatus().exitCode()).isEqualTo("COMPLETED");
		}

		@Test
		void updateTimestampShouldBeSetAutomaticallyOnPersist() {
			var flow = new AgentFlowExecution("ts-run-1", "timestamp-test");
			flowRepo.saveAndFlush(flow);
			em.clear();

			var loaded = flowRepo.findByRunId("ts-run-1").orElseThrow();
			assertThat(loaded.getLastUpdated()).isNotNull();
			assertThat(loaded.getLastUpdated()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
		}
	}

}
