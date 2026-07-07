package io.github.markpollack.workflow.flows.spec;

/**
 * Thrown when a v1 workflow cannot be emitted as a v2-alpha {@code WorkflowSpec}.
 *
 * <p>Two families of failure, both deliberate (DD-20 rationale, RISKS R2):
 * <ul>
 *   <li><b>Not expressible in v2-alpha</b> — the workflow uses a primitive the alpha
 *       IR has no vocabulary for ({@code parallel}/{@code gather}, {@code repeatUntil}/
 *       {@code repeatUntilOutput}, {@code gate}, {@code supervisor}, {@code backTo},
 *       {@code onError}). Emission fails loudly rather than silently mis-emitting a
 *       spec whose execution would diverge from {@code .run()}.</li>
 *   <li><b>Naming collision</b> — two distinct behaviors derive the same structural
 *       ref (RISKS R1). Refs must be a pure function of workflow structure (DD-21),
 *       so the fix is renaming a step, never last-wins registration.</li>
 * </ul>
 */
public class SpecEmissionException extends RuntimeException {

    public SpecEmissionException(String message) {
        super(message);
    }
}
