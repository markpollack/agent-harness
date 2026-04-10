package io.github.markpollack.workflow.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pins a stable name on a step for checkpoint and Temporal keys.
 * <p>
 * When a step class is renamed, the checkpoint key stays the same — so crash
 * recovery and Temporal replay still match the right execution record.
 * <p>
 * Pure Java annotation — no Spring dependency.
 *
 * <pre>{@code
 * @StepName("analyze-diff")
 * public class AnalyzeDiffStep implements Step<String, String> { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StepName {

    /**
     * The stable name for this step.
     */
    String value();
}
