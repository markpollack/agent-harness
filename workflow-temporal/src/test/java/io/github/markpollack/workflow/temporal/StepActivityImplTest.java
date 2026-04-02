package io.github.markpollack.workflow.temporal;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepActivityImplTest {

	private StepActivityImpl activity;

	@BeforeEach
	void setUp() {
		activity = new StepActivityImpl();
	}

	@Test
	void shouldExecuteRegisteredStep() {
		Step<String, String> step = Step.named("greet", (AgentContext ctx, String input) -> "Hello, " + input);
		activity.registerStep(step);

		Object result = activity.executeStep("greet", "run-1", "World");
		assertThat(result).isEqualTo("Hello, World");
	}

	@Test
	void shouldThrowWhenStepNotRegistered() {
		assertThatThrownBy(() -> activity.executeStep("unknown", "run-1", "input"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("No step registered with name: unknown");
	}

	@Test
	void shouldSupportMultipleSteps() {
		Step<String, String> upper = Step.named("upper", (AgentContext ctx, String input) -> input.toUpperCase());
		Step<String, String> lower = Step.named("lower", (AgentContext ctx, String input) -> input.toLowerCase());
		activity.registerStep(upper);
		activity.registerStep(lower);

		assertThat(activity.executeStep("upper", "run-1", "hello")).isEqualTo("HELLO");
		assertThat(activity.executeStep("lower", "run-1", "HELLO")).isEqualTo("hello");
	}

	@Test
	void shouldPassRunIdToAgentContext() {
		Step<String, String> step = Step.named("check-run", (AgentContext ctx, String input) -> ctx.runId());
		activity.registerStep(step);

		Object result = activity.executeStep("check-run", "my-run-id", "ignored");
		assertThat(result).isEqualTo("my-run-id");
	}

}
