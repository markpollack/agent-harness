package io.github.markpollack.workflow.flows.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a named agent and associates it with
 * {@link io.github.markpollack.workflow.core.AgentRegistry} for lookup
 * by protocol layers (MCP, A2A, HTTP).
 * <p>
 * Pure Java annotation — no framework dependency. Spring Boot
 * auto-configuration can discover {@code @Agent}-annotated beans
 * via component scanning, but the annotation itself is framework-agnostic.
 *
 * <pre>{@code
 * @Agent("code-review")
 * public class CodeReviewAgent implements AgentHandler<String, String> {
 *     public String handle(AgentContext ctx, String diff) {
 *         return Workflow.<String, String>define("code-review")
 *             .step(analyzeStep)
 *             .then(reviewStep)
 *             .run(diff, ctx);
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Agent {

    /**
     * The unique agent name within the application.
     */
    String value();
}
