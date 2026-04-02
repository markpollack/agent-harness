package io.github.markpollack.workflow.temporal;

import java.time.Duration;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.StepRunner;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StepRunner} that wraps each step execution as a Temporal Activity.
 *
 * <p>
 * This is the "Level 2" graduation path: distributed durable execution with automatic
 * retry, heartbeating, and audit-grade replay. Same workflow code as {@code LocalStepRunner}
 * and {@code CheckpointingStepRunner} — different {@code @Bean}.
 *
 * <p>
 * <b>Usage pattern</b>: This runner is designed to be called from within a Temporal
 * Workflow implementation. The workflow graph executor creates a {@code TemporalStepRunner}
 * and uses it to dispatch each step as a Temporal Activity.
 *
 * <p>
 * <b>Idempotency contract</b>: Temporal may re-execute activities on failure. Steps used
 * with this runner must be side-effect safe or idempotent.
 *
 * <p>
 * <b>Heartbeating</b>: Long-running steps (e.g., {@code ClaudeStep} which can run >24 min)
 * should be paired with the {@code AutoHeartbeatWorkerInterceptor} pattern from the
 * Temporal samples to avoid activity timeout.
 *
 * @see StepActivityStub the activity interface that Temporal proxies
 */
public final class TemporalStepRunner implements StepRunner {

	private static final Logger log = LoggerFactory.getLogger(TemporalStepRunner.class);

	private final Duration startToCloseTimeout;

	private final Duration heartbeatTimeout;

	private final String taskQueue;

	/**
	 * Creates a TemporalStepRunner with the specified configuration.
	 *
	 * @param taskQueue            the Temporal task queue for activity dispatch
	 * @param startToCloseTimeout  maximum time for a step activity to complete
	 * @param heartbeatTimeout     heartbeat interval for long-running steps
	 */
	public TemporalStepRunner(String taskQueue, Duration startToCloseTimeout, Duration heartbeatTimeout) {
		this.taskQueue = taskQueue;
		this.startToCloseTimeout = startToCloseTimeout;
		this.heartbeatTimeout = heartbeatTimeout;
	}

	/**
	 * Creates a TemporalStepRunner with default timeouts (10 minutes start-to-close,
	 * 30 seconds heartbeat).
	 *
	 * @param taskQueue the Temporal task queue for activity dispatch
	 */
	public TemporalStepRunner(String taskQueue) {
		this(taskQueue, Duration.ofMinutes(10), Duration.ofSeconds(30));
	}

	@Override
	public <I, O> O execute(Step<I, O> step, AgentContext ctx, I input) {
		log.debug("Dispatching step '{}' as Temporal activity on queue '{}'", step.name(), taskQueue);

		ActivityOptions options = ActivityOptions.newBuilder()
				.setStartToCloseTimeout(startToCloseTimeout)
				.setHeartbeatTimeout(heartbeatTimeout)
				.setTaskQueue(taskQueue)
				.build();

		StepActivityStub stub = Workflow.newActivityStub(StepActivityStub.class, options);

		@SuppressWarnings("unchecked")
		O result = (O) stub.executeStep(step.name(), ctx.runId(), input);
		return result;
	}

	public String getTaskQueue() {
		return taskQueue;
	}

	public Duration getStartToCloseTimeout() {
		return startToCloseTimeout;
	}

	public Duration getHeartbeatTimeout() {
		return heartbeatTimeout;
	}

}
