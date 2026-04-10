package io.github.markpollack.workflow.core;

/**
 * Entry-point contract for agents — the root-level interface that owns a workflow
 * and faces the external world (MCP, event bus, HTTP).
 * <p>
 * {@code AgentHandler} is to {@code Step} what {@code @Controller} is to {@code @Service}:
 * the external-facing boundary. A {@code Step} is internal plumbing within a workflow;
 * an {@code AgentHandler} is the thing you wire to an HTTP endpoint or message listener.
 * <p>
 * Intentionally a {@code @FunctionalInterface} — lambdas work for simple cases:
 * <pre>{@code
 * AgentHandler<String, String> echo = (ctx, input) -> input.toUpperCase();
 * }</pre>
 * <p>
 * For production agents, implement as a class that composes a {@code Workflow}:
 * <pre>{@code
 * public class CodeReviewAgent implements AgentHandler<String, String> {
 *     public String handle(AgentContext ctx, String diff) {
 *         return Workflow.<String, String>define("code-review")
 *             .step(analyzeStep)
 *             .then(reviewStep)
 *             .run(diff, ctx);
 *     }
 * }
 * }</pre>
 * <p>
 * Pure Java — no Spring dependency. Lives in {@code workflow-api}.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
@FunctionalInterface
public interface AgentHandler<I, O> {

    /**
     * Handles an agent invocation with the given context and input.
     *
     * @param ctx   the execution context (carries run ID, accumulated state)
     * @param input the input to handle
     * @return the result of handling
     */
    O handle(AgentContext ctx, I input);
}
