package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.AgentHandler;
import io.github.markpollack.workflow.core.ContextKey;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.steps.Steps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentHandler + Workflow integration")
class AgentHandlerWorkflowTest {

    @Nested
    @DisplayName("run(input, ctx) overloads")
    class RunWithContext {

        @Test
        void shouldInheritParentContextEntries() {
            ContextKey<String> USER_KEY = ContextKey.of("user", String.class);

            // Step that reads the user-defined key from context
            Step<String, String> readCtx = Step.named("read-ctx",
                    (ctx, input) -> ctx.require(USER_KEY) + ":" + input);

            AgentContext parentCtx = AgentContext.withRunId("parent-run")
                    .mutate()
                    .with(USER_KEY, "alice")
                    .build();

            String result = Workflow.<String, String>define("child-wf")
                    .step(readCtx)
                    .run("hello", parentCtx);

            assertThat(result).isEqualTo("alice:hello");
        }

        @Test
        void shouldGetFreshWorkflowRunId() {
            // Capture the run ID seen by the step
            String[] capturedRunId = new String[1];
            Step<String, String> capture = Step.named("capture",
                    (ctx, input) -> {
                        capturedRunId[0] = ctx.runId();
                        return input;
                    });

            AgentContext parentCtx = AgentContext.withRunId("parent-run-123");

            Workflow.<String, String>define("sub-wf")
                    .step(capture)
                    .run("input", parentCtx);

            // Sub-workflow should have a different run ID (fresh UUID)
            assertThat(capturedRunId[0]).isNotEqualTo("parent-run-123");
            assertThat(capturedRunId[0]).isNotBlank();
        }

        @Test
        void shouldSetWorkflowNameToSubWorkflowName() {
            String[] capturedName = new String[1];
            Step<String, String> capture = Step.named("capture",
                    (ctx, input) -> {
                        capturedName[0] = ctx.get(AgentContext.WORKFLOW_NAME).orElse(null);
                        return input;
                    });

            AgentContext parentCtx = AgentContext.withRunId("parent")
                    .mutate()
                    .with(AgentContext.WORKFLOW_NAME, "parent-workflow")
                    .build();

            Workflow.<String, String>define("child-workflow")
                    .step(capture)
                    .run("input", parentCtx);

            assertThat(capturedName[0]).isEqualTo("child-workflow");
        }

        @Test
        void shouldWorkWithRunOptions() {
            Step<String, String> upper = Step.named("upper",
                    (ctx, input) -> input.toUpperCase());

            AgentContext ctx = AgentContext.withRunId("test-run");
            RunOptions options = RunOptions.maxIterations(5);

            String result = Workflow.<String, String>define("opts-wf")
                    .step(upper)
                    .run("hello", ctx, options);

            assertThat(result).isEqualTo("HELLO");
        }
    }

    @Nested
    @DisplayName("AgentHandler class-based pattern")
    class ClassBasedHandler {

        @Test
        void shouldImplementHandlerWithInternalWorkflow() {
            // A class-based handler that composes a workflow internally
            AgentHandler<String, String> reviewAgent = new CodeReviewAgent();

            AgentContext ctx = AgentContext.withRunId("review-42");
            String result = reviewAgent.handle(ctx, "some diff");

            assertThat(result).contains("REVIEWED");
            assertThat(result).contains("some diff");
        }
    }

    /**
     * Example class-based AgentHandler that uses Workflow.run(input, ctx) internally.
     */
    static class CodeReviewAgent implements AgentHandler<String, String> {

        private final Step<String, String> analyzeStep = Step.named("analyze",
                (ctx, input) -> "analyzed:" + input);

        private final Step<String, String> reviewStep = Step.named("review",
                (ctx, input) -> "REVIEWED[" + input + "]");

        @Override
        public String handle(AgentContext ctx, String diff) {
            return Workflow.<String, String>define("code-review")
                    .step(analyzeStep)
                    .then(reviewStep)
                    .run(diff, ctx);
        }
    }
}
