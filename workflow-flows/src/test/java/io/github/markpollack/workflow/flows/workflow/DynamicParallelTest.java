package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.ContextKey;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicParallelTest {

    static final ContextKey<List<String>> FILES_KEY =
            ContextKey.of("files", (Class<List<String>>) (Class) List.class);

    @Test
    @SuppressWarnings("unchecked")
    void dynamicParallelShouldFanOutPerItemFromContext() {
        AgentContext ctx = AgentContext.create().mutate()
                .with(FILES_KEY, List.of("a.java", "b.java", "c.java"))
                .build();

        Workflow<String, Object> workflow = Workflow.<String, Object>define("dynamic-test")
                .parallel(
                        c -> (List<?>) c.require(FILES_KEY),
                        item -> Step.named("analyze", (c, in) -> "analyzed:" + in))
                .build();

        List<Object> result = (List<Object>) workflow.execute(ctx, "ignored");

        assertThat(result).hasSize(3);
        assertThat(result).containsExactlyInAnyOrder(
                "analyzed:a.java", "analyzed:b.java", "analyzed:c.java");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicParallelShouldProduceOneResultPerItem() {
        // Supply items directly from a step's output via context
        Step<String, List<String>> produceFiles = Step.named("produce",
                (ctx, in) -> List.of("file1.java", "file2.java", "file3.java"));

        Step<List<String>, Object> dynamicAnalyze = Step.named("fan-out", (ctx, files) -> {
            // Manually do dynamic parallel within a step
            return files.stream()
                    .map(f -> "analyzed:" + f)
                    .toList();
        });

        // Simpler: use the DSL with a context-based supplier
        Workflow<String, Object> workflow = Workflow.<String, Object>define("dynamic")
                .parallel(
                        ctx -> List.of("x", "y", "z"),
                        item -> Step.named("process-" + item,
                                (c, in) -> "processed:" + in))
                .build();

        Object result = workflow.execute(AgentContext.create(), "input");
        List<Object> results = (List<Object>) result;

        assertThat(results).hasSize(3);
        assertThat(results).containsExactlyInAnyOrder(
                "processed:x", "processed:y", "processed:z");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicParallelWithZeroItemsShouldReturnEmptyList() {
        Workflow<String, Object> workflow = Workflow.<String, Object>define("empty-dynamic")
                .parallel(
                        ctx -> List.of(),  // no items
                        item -> Step.named("never", (c, in) -> "should not run"))
                .build();

        Object result = workflow.execute(AgentContext.create(), "input");
        List<Object> results = (List<Object>) result;

        assertThat(results).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicParallelShouldChainWithOtherSteps() {
        String result = Workflow.<String, String>define("chain-dynamic")
                .step(Step.named("prepare", (ctx, in) -> ((String) in).toUpperCase()))
                .parallel(
                        ctx -> List.of("a", "b"),
                        item -> Step.named("map-" + item,
                                (c, in) -> in + ":" + item))
                .then(Step.named("join", (ctx, in) -> "joined:" + in))
                .run("start");

        assertThat(result).startsWith("joined:");
    }
}
