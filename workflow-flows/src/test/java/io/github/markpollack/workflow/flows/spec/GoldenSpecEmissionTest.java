package io.github.markpollack.workflow.flows.spec;

import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.spec.CanonicalJson;
import io.github.markpollack.workflow.spec.DefaultWorkflowSpecWriter;
import io.github.markpollack.workflow.spec.TerminateStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * The Java side of the cross-SDK equivalence contract (roadmap Step 3.1; SDK steps
 * P2.2/T2.2 compare against the same fixture): the golden pr-review workflow, authored
 * through the v1 DSL and emitted with explicit portability-ladder customization, is
 * canonically byte-equal to {@code spec/fixtures/valid/golden-pr-review.json}.
 *
 * <p>The fixture is read from the Conformance Kit at the repo root ({@code ../spec});
 * Surefire's working directory is the module basedir.
 */
class GoldenSpecEmissionTest {

    private static final Path GOLDEN_FIXTURE =
            Path.of("..", "spec", "fixtures", "valid", "golden-pr-review.json");

    @Test
    void emittedGoldenWorkflowIsCanonicallyByteEqualToTheFixture() throws Exception {
        ChatClient router = mock(ChatClient.class, RETURNS_DEEP_STUBS); // routing is never invoked here

        Workflow<Object, Object> workflow = Workflow.define("pr-review")
                .step(Step.named("fetch_diff", (ctx, in) -> in))
                .then(Step.named("analyze_diff", (ctx, in) -> in))
                .decision(router)
                    .option("post", Step.named("post_comment", (ctx, in) -> in))
                .end()
                .build();

        SpecEmitterOptions options = SpecEmitterOptions.builder()
                .version("1.0.0")
                .constant("approval_threshold", 0.8)
                .node("fetch_diff", n -> n
                        .operation("fetch-pr-diff", "java:github.fetch_pr_diff:v1")
                        .input("url", "$input.url")
                        .contextWrite("pr.diff", "$node.fetch_diff.output"))
                .node("analyze_diff", n -> n
                        .operation("analyze-diff", "python:review.analyze_diff:v2")
                        .input("diff", "$context.pr.diff"))
                .node("decision-0", n -> n
                        .id("route")
                        .operation("route-review", "java:review.route:v1")
                        .input("analysis", "$node.analyze_diff.output")
                        .input("threshold", "$const.approval_threshold")
                        .outcomeTerminate("fail", TerminateStatus.FAILED,
                                "rejected", "$node.analyze_diff.output"))
                .node("post_comment", n -> n
                        .operation("post-review", "typescript:github.post_review:v1")
                        .input("review", "$node.analyze_diff.output"))
                .build();

        ByteArrayOutputStream emitted = new ByteArrayOutputStream();
        new DefaultWorkflowSpecWriter().write(workflow.toSpec(options).spec(), emitted);

        byte[] golden = CanonicalJson.canonicalize(Files.readAllBytes(GOLDEN_FIXTURE));

        assertThat(new String(emitted.toByteArray(), StandardCharsets.UTF_8))
                .isEqualTo(new String(golden, StandardCharsets.UTF_8));
        assertThat(emitted.toByteArray()).isEqualTo(golden);
    }
}
