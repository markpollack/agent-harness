package io.github.markpollack.workflow.flows.agent;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Spring stereotype for {@link io.github.markpollack.workflow.core.AgentHandler} beans.
 * <p>
 * Marks a class as an agent and registers it in the Spring application context.
 * The {@link #value()} is the unique agent name used for lookup in
 * {@link io.github.markpollack.workflow.core.AgentRegistry}.
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
@Component
public @interface Agent {

    /**
     * The unique agent name within the application.
     */
    String value();
}
