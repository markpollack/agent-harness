package io.github.markpollack.workflow.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an exception handler for agent invocations.
 * <p>
 * Mirrors the Spring MVC {@code @ExceptionHandler} pattern exactly:
 * methods annotated with this annotation handle exceptions thrown by
 * {@link AgentHandler#handle(AgentContext, Object)}.
 * <p>
 * Supported inside {@code @Agent} classes (per-agent handling) and
 * {@code @AgentAdvice} classes (cross-cutting handling). Per-agent
 * handlers take priority over cross-cutting handlers.
 * <p>
 * Handler method signatures:
 * <ul>
 *   <li>{@code ReturnType method(ExceptionType ex)}</li>
 *   <li>{@code ReturnType method(ExceptionType ex, AgentContext ctx)}</li>
 * </ul>
 *
 * <pre>{@code
 * @Agent("code-review")
 * public class CodeReviewAgent implements AgentHandler<String, String> {
 *     public String handle(AgentContext ctx, String input) { ... }
 *
 *     @ExceptionHandler(RateLimitException.class)
 *     public String handleRateLimit(RateLimitException ex, AgentContext ctx) {
 *         return "Rate limited — try again later";
 *     }
 * }
 * }</pre>
 * <p>
 * Pure Java annotation — no Spring dependency.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExceptionHandler {

    /**
     * Exception types handled by this method.
     * <p>
     * If empty, the method handles the exception type declared as its
     * first parameter.
     */
    Class<? extends Throwable>[] value() default {};
}
