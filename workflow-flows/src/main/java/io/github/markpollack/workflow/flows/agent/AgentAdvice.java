package io.github.markpollack.workflow.flows.agent;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a cross-cutting exception handler for all agents.
 * <p>
 * Mirrors Spring MVC's {@code @ControllerAdvice} pattern exactly.
 * Methods annotated with {@link io.github.markpollack.workflow.core.ExceptionHandler}
 * inside an {@code @AgentAdvice} class handle exceptions from any agent.
 * <p>
 * Per-agent handlers (inside {@code @Agent} classes) take priority over
 * cross-cutting handlers.
 *
 * <pre>{@code
 * @AgentAdvice
 * public class GlobalErrorHandler {
 *     @ExceptionHandler(Exception.class)
 *     public Object handleAny(Exception ex, AgentContext ctx) {
 *         log.error("Agent {} failed", ctx.runId(), ex);
 *         return "An error occurred: " + ex.getMessage();
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface AgentAdvice {
}
