package io.github.markpollack.workflow.flows.agent;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.AgentHandler;
import io.github.markpollack.workflow.core.AgentRegistry;
import io.github.markpollack.workflow.core.Description;
import io.github.markpollack.workflow.core.ExceptionHandler;
import io.github.markpollack.workflow.core.StepName;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full annotation model example — exercises all 5 annotations plus
 * AgentRegistry, AgentExceptionHandlerResolver, and StepNames together.
 * <p>
 * This is the "PR Review Agent" scenario from the Step 4.5 consolidation.
 */
@DisplayName("Full annotation model example")
class AnnotationModelExampleTest {

    // -- Domain types --

    record PrEvent(String prUrl, String diff) {}
    record ReviewReport(String summary, String recommendation) {}

    // -- Annotated steps --

    @StepName("analyze-diff")
    @Description("Parses a PR diff and extracts changed files and hunks")
    static class AnalyzeDiffStep implements Step<PrEvent, String> {
        @Override
        public String execute(AgentContext ctx, PrEvent input) {
            return "analyzed:" + input.diff();
        }

        @Override
        public String name() {
            return "AnalyzeDiffStep";
        }
    }

    @StepName("produce-review")
    @Description("Produces a structured review report from analysis")
    static class ProduceReviewStep implements Step<String, ReviewReport> {
        @Override
        public ReviewReport execute(AgentContext ctx, String analysis) {
            return new ReviewReport(analysis, "LGTM");
        }

        @Override
        public String name() {
            return "ProduceReviewStep";
        }
    }

    // -- Annotated agent --

    @Agent("pr-review")
    @Description("Reviews a pull request and produces a structured report")
    static class PrReviewAgent implements AgentHandler<PrEvent, ReviewReport> {

        private final Step<PrEvent, String> analyzeStep = new AnalyzeDiffStep();
        private final Step<String, ReviewReport> reviewStep = new ProduceReviewStep();

        @Override
        public ReviewReport handle(AgentContext ctx, PrEvent event) {
            return Workflow.<PrEvent, ReviewReport>define("pr-review-workflow")
                    .step(analyzeStep)
                    .then(reviewStep)
                    .run(event, ctx);
        }

        @ExceptionHandler(IllegalStateException.class)
        public ReviewReport handleBudgetExceeded(IllegalStateException ex) {
            return new ReviewReport("Budget exceeded: " + ex.getMessage(), "SKIP");
        }
    }

    // -- Cross-cutting advice --

    @AgentAdvice
    static class GlobalErrorAdvice {
        @ExceptionHandler(RuntimeException.class)
        public Object handleRuntime(RuntimeException ex, AgentContext ctx) {
            return new ReviewReport("Error: " + ex.getMessage(), "ERROR");
        }
    }

    // -- Tests --

    @Test
    void shouldRunFullAnnotatedAgentWorkflow() {
        PrReviewAgent agent = new PrReviewAgent();
        PrEvent event = new PrEvent("https://github.com/org/repo/pull/42", "diff content");

        ReviewReport report = agent.handle(AgentContext.create(), event);

        assertThat(report.summary()).isEqualTo("analyzed:diff content");
        assertThat(report.recommendation()).isEqualTo("LGTM");
    }

    @Test
    void shouldLookupAgentByNameInRegistry() {
        PrReviewAgent agent = new PrReviewAgent();
        AgentRegistry registry = new AgentRegistry(Map.of("pr-review", agent));

        AgentHandler<?, ?> found = registry.get("pr-review").orElseThrow();

        assertThat(found).isSameAs(agent);
    }

    @Test
    void shouldResolveStepNamesFromAnnotation() {
        assertThat(StepNames.resolve(new AnalyzeDiffStep())).isEqualTo("analyze-diff");
        assertThat(StepNames.resolve(new ProduceReviewStep())).isEqualTo("produce-review");
    }

    @Test
    void shouldReadDescriptionAnnotation() {
        Description desc = PrReviewAgent.class.getAnnotation(Description.class);

        assertThat(desc).isNotNull();
        assertThat(desc.value()).isEqualTo("Reviews a pull request and produces a structured report");
    }

    @Test
    void shouldReadAgentAnnotation() {
        Agent agentAnn = PrReviewAgent.class.getAnnotation(Agent.class);

        assertThat(agentAnn).isNotNull();
        assertThat(agentAnn.value()).isEqualTo("pr-review");
    }

    @Test
    void shouldHandleExceptionViaPerAgentHandler() {
        PrReviewAgent agent = new PrReviewAgent();
        var resolver = new AgentExceptionHandlerResolver();

        var result = resolver.resolve(agent,
                new IllegalStateException("$5 limit exceeded"), AgentContext.create());

        assertThat(result).isPresent();
        ReviewReport report = (ReviewReport) result.get();
        assertThat(report.recommendation()).isEqualTo("SKIP");
    }

    @Test
    void shouldFallBackToCrossCuttingAdvice() {
        PrReviewAgent agent = new PrReviewAgent();
        var resolver = new AgentExceptionHandlerResolver(List.of(new GlobalErrorAdvice()));

        // NullPointerException → no per-agent handler, global advice catches it
        var result = resolver.resolve(agent,
                new NullPointerException("null ref"), AgentContext.create());

        assertThat(result).isPresent();
        ReviewReport report = (ReviewReport) result.get();
        assertThat(report.recommendation()).isEqualTo("ERROR");
    }
}
