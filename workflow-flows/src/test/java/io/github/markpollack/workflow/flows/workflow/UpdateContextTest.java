package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateContextTest {

    // =========================================================================
    // Token budget tracking
    // =========================================================================

    @Nested
    class TokenTracking {

        static final ContextKey<Long> TOKENS_USED =
                ContextKey.of("trackedLlm.tokensUsed", Long.class);

        static class TrackedLlmStep implements Step<String, String> {
            private long lastTokenCount;

            @Override public String name() { return "tracked-llm"; }

            @Override
            public String execute(AgentContext ctx, String input) {
                // Simulate LLM call that produces tokens as a side effect
                lastTokenCount = input.length() * 10L;
                return "response to: " + input;
            }

            @Override
            public AgentContext updateContext(AgentContext ctx, String output) {
                return ctx.mutate().with(TOKENS_USED, lastTokenCount).build();
            }
        }

        @Test
        void downstreamStepShouldReadTokenCount() {
            AtomicReference<Long> capturedTokens = new AtomicReference<>();

            Workflow.<String, String>define("token-tracking")
                    .step(new TrackedLlmStep())
                    .then(Step.named("report", (ctx, in) -> {
                        capturedTokens.set(ctx.get(TOKENS_USED).orElse(-1L));
                        return in;
                    }))
                    .run("hello");

            assertThat(capturedTokens.get()).isEqualTo(50L); // "hello".length() * 10
        }
    }

    // =========================================================================
    // Language detection as side-channel
    // =========================================================================

    @Nested
    class LanguageDetection {

        static final ContextKey<String> DETECTED_LANGUAGE =
                ContextKey.of("translate.detectedLanguage", String.class);

        static class TranslateStep implements Step<String, String> {
            private String detectedLang;

            @Override public String name() { return "translate"; }

            @Override
            public String execute(AgentContext ctx, String input) {
                // Simulate: detect language and translate
                detectedLang = input.startsWith("Bonjour") ? "French" : "English";
                return "translated: " + input;
            }

            @Override
            public AgentContext updateContext(AgentContext ctx, String output) {
                return ctx.mutate().with(DETECTED_LANGUAGE, detectedLang).build();
            }
        }

        @Test
        void auditStepShouldReadDetectedLanguage() {
            AtomicReference<String> capturedLang = new AtomicReference<>();

            Workflow.<String, String>define("translate-pipeline")
                    .step(new TranslateStep())
                    .then(Step.named("audit", (ctx, in) -> {
                        capturedLang.set(ctx.get(DETECTED_LANGUAGE).orElse("unknown"));
                        return in;
                    }))
                    .run("Bonjour le monde");

            assertThat(capturedLang.get()).isEqualTo("French");
        }

        @Test
        void englishInputShouldDetectEnglish() {
            AtomicReference<String> capturedLang = new AtomicReference<>();

            Workflow.<String, String>define("translate-en")
                    .step(new TranslateStep())
                    .then(Step.named("audit", (ctx, in) -> {
                        capturedLang.set(ctx.get(DETECTED_LANGUAGE).orElse("unknown"));
                        return in;
                    }))
                    .run("Hello world");

            assertThat(capturedLang.get()).isEqualTo("English");
        }
    }

    // =========================================================================
    // Classification confidence as side-channel
    // =========================================================================

    @Nested
    class ClassificationConfidence {

        static final ContextKey<Double> CONFIDENCE =
                ContextKey.of("classifier.confidence", Double.class);
        static final ContextKey<String> REASONING =
                ContextKey.of("classifier.reasoning", String.class);

        static class ClassifierStep implements Step<String, String> {
            private double confidence;
            private String reasoning;

            @Override public String name() { return "classifier"; }

            @Override
            public String execute(AgentContext ctx, String input) {
                // Simulate classification with confidence
                if (input.toLowerCase().contains("leg") || input.toLowerCase().contains("pain")) {
                    confidence = 0.95;
                    reasoning = "Physical injury symptoms described";
                    return "medical";
                } else {
                    confidence = 0.7;
                    reasoning = "Contractual language detected";
                    return "legal";
                }
            }

            @Override
            public AgentContext updateContext(AgentContext ctx, String output) {
                return ctx.mutate()
                        .with(CONFIDENCE, confidence)
                        .with(REASONING, reasoning)
                        .build();
            }
        }

        @Test
        void branchAndAuditShouldReadConfidenceFromContext() {
            AtomicReference<Double> capturedConfidence = new AtomicReference<>();
            AtomicReference<String> capturedReasoning = new AtomicReference<>();

            // Classifier → branch → audit (audit reads confidence regardless of branch)
            Workflow.<String, String>define("confident-classifier")
                    .step(new ClassifierStep())
                    .branch(output -> "medical".equals(output))
                        .then(Step.named("medical-expert", (ctx, in) -> "medical advice"))
                        .otherwise(Step.named("legal-expert", (ctx, in) -> "legal advice"))
                    .then(Step.named("audit", (ctx, in) -> {
                        capturedConfidence.set(ctx.get(CONFIDENCE).orElse(-1.0));
                        capturedReasoning.set(ctx.get(REASONING).orElse("none"));
                        return in;
                    }))
                    .run("I broke my leg");

            assertThat(capturedConfidence.get()).isEqualTo(0.95);
            assertThat(capturedReasoning.get()).isEqualTo("Physical injury symptoms described");
        }

        @Test
        void legalInputShouldHaveLowerConfidence() {
            AtomicReference<Double> capturedConfidence = new AtomicReference<>();

            Workflow.<String, String>define("legal-classify")
                    .step(new ClassifierStep())
                    .then(Step.named("reader", (ctx, in) -> {
                        capturedConfidence.set(ctx.get(CONFIDENCE).orElse(-1.0));
                        return in;
                    }))
                    .run("breach of contract clause");

            assertThat(capturedConfidence.get()).isEqualTo(0.7);
        }
    }

    // =========================================================================
    // Source tracking for citation
    // =========================================================================

    @Nested
    class SourceTracking {

        static final ContextKey<List<String>> SOURCES_CONSULTED =
                ContextKey.of("research.sourcesConsulted", (Class<List<String>>) (Class<?>) List.class);

        static class ResearchStep implements Step<String, String> {
            private List<String> sources;

            @Override public String name() { return "research"; }

            @Override
            public String execute(AgentContext ctx, String input) {
                sources = List.of("Wikipedia: " + input, "PubMed: " + input, "ArXiv: " + input);
                return "Synthesized answer about " + input;
            }

            @Override
            public AgentContext updateContext(AgentContext ctx, String output) {
                return ctx.mutate().with(SOURCES_CONSULTED, sources).build();
            }
        }

        @Test
        void citationStepShouldReadSources() {
            AtomicReference<List<String>> capturedSources = new AtomicReference<>();

            Workflow.<String, String>define("research-pipeline")
                    .step(new ResearchStep())
                    .then(Step.named("citation", (ctx, in) -> {
                        @SuppressWarnings("unchecked")
                        List<String> sources = (List<String>) (Object)
                                ctx.get(SOURCES_CONSULTED).orElse(List.of());
                        capturedSources.set(sources);
                        return in + "\nSources: " + String.join(", ", sources);
                    }))
                    .run("machine learning");

            assertThat(capturedSources.get()).hasSize(3);
            assertThat(capturedSources.get().get(0)).contains("Wikipedia");
        }
    }

    // =========================================================================
    // Multiple steps writing to context — keys don't collide
    // =========================================================================

    @Nested
    class MultipleWriters {

        static final ContextKey<String> STEP_A_META =
                ContextKey.of("stepA.meta", String.class);
        static final ContextKey<String> STEP_B_META =
                ContextKey.of("stepB.meta", String.class);

        @Test
        void multipleStepsShouldWriteIndependentKeys() {
            AtomicReference<String> fromA = new AtomicReference<>();
            AtomicReference<String> fromB = new AtomicReference<>();

            Step<String, String> stepA = new Step<>() {
                @Override public String name() { return "step-a"; }
                @Override public String execute(AgentContext ctx, String in) { return "A:" + in; }
                @Override public AgentContext updateContext(AgentContext ctx, String output) {
                    return ctx.mutate().with(STEP_A_META, "metadata-from-A").build();
                }
            };

            Step<String, String> stepB = new Step<>() {
                @Override public String name() { return "step-b"; }
                @Override public String execute(AgentContext ctx, String in) { return "B:" + in; }
                @Override public AgentContext updateContext(AgentContext ctx, String output) {
                    return ctx.mutate().with(STEP_B_META, "metadata-from-B").build();
                }
            };

            Workflow.<String, String>define("multi-writer")
                    .step(stepA)
                    .then(stepB)
                    .then(Step.named("reader", (ctx, in) -> {
                        fromA.set(ctx.get(STEP_A_META).orElse("missing"));
                        fromB.set(ctx.get(STEP_B_META).orElse("missing"));
                        return in;
                    }))
                    .run("input");

            assertThat(fromA.get()).isEqualTo("metadata-from-A");
            assertThat(fromB.get()).isEqualTo("metadata-from-B");
        }
    }
}
