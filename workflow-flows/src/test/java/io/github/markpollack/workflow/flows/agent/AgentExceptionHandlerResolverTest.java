package io.github.markpollack.workflow.flows.agent;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.AgentHandler;
import io.github.markpollack.workflow.core.ExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentExceptionHandlerResolver")
class AgentExceptionHandlerResolverTest {

    // -- Test fixtures --

    static class TestAgent implements AgentHandler<String, String> {
        @Override
        public String handle(AgentContext ctx, String input) {
            throw new IllegalArgumentException("boom");
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public String handleIAE(IllegalArgumentException ex) {
            return "per-agent: " + ex.getMessage();
        }
    }

    static class AgentWithContextHandler implements AgentHandler<String, String> {
        @Override
        public String handle(AgentContext ctx, String input) {
            throw new IllegalStateException("state-boom");
        }

        @ExceptionHandler(IllegalStateException.class)
        public String handleISE(IllegalStateException ex, AgentContext ctx) {
            return "handled with runId=" + ctx.runId();
        }
    }

    static class AgentWithoutHandler implements AgentHandler<String, String> {
        @Override
        public String handle(AgentContext ctx, String input) {
            throw new RuntimeException("unhandled");
        }
    }

    static class AgentWithInferredType implements AgentHandler<String, String> {
        @Override
        public String handle(AgentContext ctx, String input) {
            throw new ArithmeticException("div by zero");
        }

        @ExceptionHandler
        public String handleArithmetic(ArithmeticException ex) {
            return "inferred: " + ex.getMessage();
        }
    }

    static class AgentWithHierarchy implements AgentHandler<String, String> {
        @Override
        public String handle(AgentContext ctx, String input) {
            throw new IllegalArgumentException("specific");
        }

        @ExceptionHandler(RuntimeException.class)
        public String handleRuntime(RuntimeException ex) {
            return "runtime";
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public String handleIAE(IllegalArgumentException ex) {
            return "iae";
        }
    }

    @AgentAdvice
    static class GlobalAdvice {
        @ExceptionHandler(Exception.class)
        public Object handleAll(Exception ex) {
            return "global: " + ex.getMessage();
        }
    }

    @AgentAdvice
    static class SpecificAdvice {
        @ExceptionHandler(UnsupportedOperationException.class)
        public Object handleUnsupported(UnsupportedOperationException ex) {
            return "unsupported: " + ex.getMessage();
        }
    }

    // -- Tests --

    @Test
    void shouldInvokePerAgentHandlerOnMatchingException() {
        var resolver = new AgentExceptionHandlerResolver();
        var agent = new TestAgent();

        Optional<Object> result = resolver.resolve(agent,
                new IllegalArgumentException("test"), AgentContext.create());

        assertThat(result).contains("per-agent: test");
    }

    @Test
    void shouldPassContextToHandlerMethod() {
        var resolver = new AgentExceptionHandlerResolver();
        var agent = new AgentWithContextHandler();
        AgentContext ctx = AgentContext.withRunId("ctx-42");

        Optional<Object> result = resolver.resolve(agent,
                new IllegalStateException("x"), ctx);

        assertThat(result).contains("handled with runId=ctx-42");
    }

    @Test
    void shouldReturnEmptyWhenNoHandlerMatches() {
        var resolver = new AgentExceptionHandlerResolver();
        var agent = new AgentWithoutHandler();

        Optional<Object> result = resolver.resolve(agent,
                new RuntimeException("oops"), AgentContext.create());

        assertThat(result).isEmpty();
    }

    @Test
    void shouldInferExceptionTypeFromParameter() {
        var resolver = new AgentExceptionHandlerResolver();
        var agent = new AgentWithInferredType();

        Optional<Object> result = resolver.resolve(agent,
                new ArithmeticException("div"), AgentContext.create());

        assertThat(result).contains("inferred: div");
    }

    @Test
    void shouldPreferMostSpecificExceptionType() {
        var resolver = new AgentExceptionHandlerResolver();
        var agent = new AgentWithHierarchy();

        Optional<Object> result = resolver.resolve(agent,
                new IllegalArgumentException("specific"), AgentContext.create());

        assertThat(result).contains("iae");
    }

    @Test
    void shouldFallBackToLessSpecificHandler() {
        var resolver = new AgentExceptionHandlerResolver();
        var agent = new AgentWithHierarchy();

        // NumberFormatException extends IllegalArgumentException — IAE handler should match
        Optional<Object> result = resolver.resolve(agent,
                new NumberFormatException("nfe"), AgentContext.create());

        assertThat(result).contains("iae");
    }

    @Test
    void shouldInvokeCrossCuttingAdvice() {
        var resolver = new AgentExceptionHandlerResolver(List.of(new GlobalAdvice()));
        var agent = new AgentWithoutHandler();

        Optional<Object> result = resolver.resolve(agent,
                new RuntimeException("global-test"), AgentContext.create());

        assertThat(result).contains("global: global-test");
    }

    @Test
    void shouldPreferPerAgentOverCrossCutting() {
        var resolver = new AgentExceptionHandlerResolver(List.of(new GlobalAdvice()));
        var agent = new TestAgent();

        Optional<Object> result = resolver.resolve(agent,
                new IllegalArgumentException("priority"), AgentContext.create());

        assertThat(result).contains("per-agent: priority");
    }

    @Test
    void shouldTryCrossCuttingInOrder() {
        var resolver = new AgentExceptionHandlerResolver(
                List.of(new SpecificAdvice(), new GlobalAdvice()));
        var agent = new AgentWithoutHandler();

        // UnsupportedOperationException → SpecificAdvice matches first
        Optional<Object> result = resolver.resolve(agent,
                new UnsupportedOperationException("unsup"), AgentContext.create());

        assertThat(result).contains("unsupported: unsup");
    }

    @Test
    void shouldFallThroughToNextAdvice() {
        var resolver = new AgentExceptionHandlerResolver(
                List.of(new SpecificAdvice(), new GlobalAdvice()));
        var agent = new AgentWithoutHandler();

        // RuntimeException → SpecificAdvice doesn't match, GlobalAdvice does
        Optional<Object> result = resolver.resolve(agent,
                new RuntimeException("fallthrough"), AgentContext.create());

        assertThat(result).contains("global: fallthrough");
    }
}
