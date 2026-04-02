package io.github.markpollack.workflow.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal Activity interface for executing workflow steps. Each step is dispatched
 * as a Temporal Activity through this interface.
 *
 * <p>
 * The activity worker registers a {@link StepActivityImpl} that resolves the step by name
 * from a registry and delegates to {@code step.execute()}.
 */
@ActivityInterface
public interface StepActivityStub {

	/**
	 * Executes a named step with the given input.
	 *
	 * @param stepName the name of the step to execute
	 * @param runId    the workflow run identifier
	 * @param input    the step input (must be serializable by Temporal's data converter)
	 * @return the step output
	 */
	@ActivityMethod
	Object executeStep(String stepName, String runId, Object input);

}
