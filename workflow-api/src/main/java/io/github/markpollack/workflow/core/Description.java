package io.github.markpollack.workflow.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Human-readable description for traces and visualization.
 * <p>
 * Applied to step or agent classes to provide a description that appears
 * in workflow traces, dashboards, and documentation generators.
 * <p>
 * Pure Java annotation — no Spring dependency.
 *
 * <pre>{@code
 * @Description("Analyzes a PR diff and produces a structured review")
 * public class AnalyzeDiffStep implements Step<String, String> { ... }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Description {

    /**
     * The human-readable description.
     */
    String value();
}
