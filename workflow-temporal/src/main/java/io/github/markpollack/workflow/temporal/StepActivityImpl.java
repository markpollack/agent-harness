package io.github.markpollack.workflow.temporal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Activity implementation that resolves steps by name from a registry and delegates
 * to {@code step.execute()}.
 *
 * <p>
 * Register steps before starting the Temporal worker:
 * <pre>{@code
 * var activity = new StepActivityImpl();
 * activity.registerStep(classifyStep);
 * activity.registerStep(summarizeStep);
 *
 * worker.registerActivitiesImplementations(activity);
 * }</pre>
 */
public class StepActivityImpl implements StepActivityStub {

	private static final Logger log = LoggerFactory.getLogger(StepActivityImpl.class);

	private final Map<String, Step<?, ?>> stepRegistry = new ConcurrentHashMap<>();

	/**
	 * Registers a step in the activity registry. Steps are resolved by
	 * {@link Step#name()} during activity execution.
	 *
	 * @param step the step to register
	 */
	public void registerStep(Step<?, ?> step) {
		stepRegistry.put(step.name(), step);
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Object executeStep(String stepName, String runId, Object input) {
		Step step = stepRegistry.get(stepName);
		if (step == null) {
			throw new IllegalArgumentException("No step registered with name: " + stepName);
		}

		log.debug("Executing step '{}' for run '{}'", stepName, runId);
		AgentContext ctx = AgentContext.withRunId(runId);
		return step.execute(ctx, input);
	}

}
