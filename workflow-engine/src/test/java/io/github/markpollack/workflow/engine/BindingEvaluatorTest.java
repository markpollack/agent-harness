package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.spec.Binding;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §12 resolution for all five frozen sources; §13 deterministic failures for missing
 * paths. Null values are missing (the wire has no null), never resolved nulls.
 */
class BindingEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowContextStore context =
            new AgentContextAdapter(AgentContext.withRunId("run-1")).put("pr.diff", "diff text");

    private static Object value(BindingResolution resolution) {
        return ((BindingResolution.Resolved) resolution).value();
    }

    private static String reason(BindingResolution resolution) {
        return ((BindingResolution.Failed) resolution).reason();
    }

    @Test
    void wholeInputResolves() {
        BindingEvaluator evaluator = new BindingEvaluator(Map.of("url", "https://x"), null);

        BindingResolution resolution = evaluator.resolve(new Binding("$input"), context);

        assertThat(value(resolution)).isEqualTo(Map.of("url", "https://x"));
    }

    @Test
    void inputFieldResolvesFromMapJsonNodeAndPojo() throws Exception {
        record PrRequest(String url, int number) {
        }
        BindingEvaluator fromMap = new BindingEvaluator(Map.of("url", "https://x"), null);
        BindingEvaluator fromNode = new BindingEvaluator(
                MAPPER.readTree("{\"url\": \"https://x\", \"n\": 7}"), null);
        BindingEvaluator fromPojo = new BindingEvaluator(new PrRequest("https://x", 7), null);

        assertThat(value(fromMap.resolve(new Binding("$input.url"), context))).isEqualTo("https://x");
        assertThat(value(fromNode.resolve(new Binding("$input.url"), context))).isEqualTo("https://x");
        assertThat(value(fromNode.resolve(new Binding("$input.n"), context))).isEqualTo(7);
        assertThat(value(fromPojo.resolve(new Binding("$input.url"), context))).isEqualTo("https://x");
        assertThat(value(fromPojo.resolve(new Binding("$input.number"), context))).isEqualTo(7);
    }

    @Test
    void missingInputAndMissingFieldFailDeterministically() {
        BindingEvaluator noInput = new BindingEvaluator(null, null);
        BindingEvaluator withInput = new BindingEvaluator(Map.of("url", "https://x"), null);

        assertThat(reason(noInput.resolve(new Binding("$input"), context)))
                .contains("$input");
        assertThat(reason(noInput.resolve(new Binding("$input.url"), context)))
                .contains("$input.url");
        assertThat(reason(withInput.resolve(new Binding("$input.absent"), context)))
                .isEqualTo("missing source path: $input.absent");
    }

    @Test
    void scalarInputCannotBeFieldAccessed() {
        BindingEvaluator evaluator = new BindingEvaluator("just a string", null);

        assertThat(reason(evaluator.resolve(new Binding("$input.url"), context)))
                .isEqualTo("input is not an object: $input.url");
    }

    @Test
    void contextKeyWithDotsIsOneFlatKey() {
        BindingEvaluator evaluator = new BindingEvaluator(null, null);

        BindingResolution resolution = evaluator.resolve(new Binding("$context.pr.diff"), context);

        assertThat(value(resolution)).isEqualTo("diff text");
    }

    @Test
    void missingContextKeyFails() {
        BindingEvaluator evaluator = new BindingEvaluator(null, null);

        assertThat(reason(evaluator.resolve(new Binding("$context.absent"), context)))
                .isEqualTo("missing source path: $context.absent");
    }

    @Test
    void constantsResolveToPlainJavaValues() throws Exception {
        BindingEvaluator evaluator = new BindingEvaluator(null,
                MAPPER.readTree("{\"model\": \"claude-fable-5\", \"maxTokens\": 4096, \"pinned.flag\": true}"));

        assertThat(value(evaluator.resolve(new Binding("$const.model"), context)))
                .isEqualTo("claude-fable-5");
        assertThat(value(evaluator.resolve(new Binding("$const.maxTokens"), context)))
                .isEqualTo(4096);
        assertThat(value(evaluator.resolve(new Binding("$const.pinned.flag"), context)))
                .isEqualTo(true);
    }

    @Test
    void missingConstantFailsWithAndWithoutConstantsSection() throws Exception {
        BindingEvaluator noConstants = new BindingEvaluator(null, null);
        BindingEvaluator withConstants = new BindingEvaluator(null, MAPPER.readTree("{\"a\": 1}"));

        assertThat(reason(noConstants.resolve(new Binding("$const.model"), context)))
                .contains("$const.model");
        assertThat(reason(withConstants.resolve(new Binding("$const.model"), context)))
                .isEqualTo("missing source path: $const.model");
    }

    @Test
    void nodeOutputAndDecisionResolveAfterRecording() {
        BindingEvaluator evaluator = new BindingEvaluator(null, null);
        evaluator.recordOutput("fetch", Map.of("diff", "text"));
        evaluator.recordDecision("route", "post");

        assertThat(value(evaluator.resolve(new Binding("$node.fetch.output"), context)))
                .isEqualTo(Map.of("diff", "text"));
        assertThat(value(evaluator.resolve(new Binding("$node.route.decision"), context)))
                .isEqualTo("post");
    }

    @Test
    void unexecutedNodeAndUnrecordedDecisionFail() {
        BindingEvaluator evaluator = new BindingEvaluator(null, null);
        evaluator.recordOutput("fetch", "out");

        assertThat(reason(evaluator.resolve(new Binding("$node.ghost.output"), context)))
                .contains("$node.ghost.output");
        assertThat(reason(evaluator.resolve(new Binding("$node.fetch.decision"), context)))
                .contains("$node.fetch.decision");
    }

    @Test
    void nullNodeOutputIsMissingNotResolvedNull() {
        BindingEvaluator evaluator = new BindingEvaluator(null, null);
        evaluator.recordOutput("void-step", null);

        BindingResolution resolution =
                evaluator.resolve(new Binding("$node.void-step.output"), context);

        assertThat(resolution).isInstanceOf(BindingResolution.Failed.class);
        assertThat(reason(resolution)).contains("node output is empty");
    }
}
