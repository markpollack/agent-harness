package io.github.markpollack.workflow.flows.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a cross-cutting exception handler for all agents.
 * <p>
 * Mirrors the {@code @ControllerAdvice} pattern: methods annotated with
 * {@link io.github.markpollack.workflow.core.ExceptionHandler} inside an
 * {@code @AgentAdvice} class handle exceptions from any agent.
 * <p>
 * Per-agent handlers (inside {@code @Agent} classes) take priority over
 * cross-cutting handlers.
 * <p>
 * Pure Java annotation — no framework dependency.
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
public @interface AgentAdvice {
}
