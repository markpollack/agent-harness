/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.flows;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;

import java.util.function.BiFunction;

/**
 * The core unit of work in a workflow.
 * <p>
 * {@code Step} is a named, typed, composable function from {@code I} to {@code O}.
 * All step implementations — plain Java functions, LLM calls, agentic loops,
 * and graph compositions — implement this interface.
 * <p>
 * The {@link #name()} method returns a stable identifier used in traces, error
 * messages, and visualization. For production steps used with
 * {@code CheckpointingStepRunner} or {@code TemporalStepRunner},
 * the name must be stable across JVM restarts (it serves as the checkpoint key).
 * <p>
 * Step implementations:
 * <ul>
 *   <li>{@link io.github.markpollack.workflow.flows.steps.Steps} — plain Java function, no LLM</li>
 *   <li>{@link io.github.markpollack.workflow.flows.steps.ClaudeStep} — full agentic loop via Claude CLI</li>
 *   <li>{@link io.github.markpollack.workflow.flows.steps.ChatClientStep} — single structured LLM call via spring-ai</li>
 *   <li>{@link io.github.markpollack.workflow.flows.steps.AgentClientStep} — via AgentClient abstraction</li>
 *   <li>{@link io.github.markpollack.workflow.flows.steps.GraphStep} — wraps a GraphCompositionStrategy</li>
 * </ul>
 *
 * @param <I> the input type
 * @param <O> the output type
 */
@FunctionalInterface
public interface Step<I, O> {

    /**
     * Executes this step with the given context and input.
     *
     * @param ctx   the shared execution context for this workflow run
     * @param input the input to this step (typically the output of the previous step)
     * @return the output of this step
     */
    O execute(AgentContext ctx, I input);

    /**
     * Returns the name of this step.
     * <p>
     * Appears in traces, error messages, and visualization. Default implementation
     * returns the simple class name. Override in subclasses or use
     * {@link #named(String, BiFunction)} for lambda steps.
     *
     * @return the step name
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Declared input type. Defaults to {@code Object.class} (opt-out).
     * Override to enable {@code WorkflowGraphAssert.assertTypeCompatible()} checks.
     */
    default Class<?> inputType() {
        return Object.class;
    }

    /**
     * Declared output type. Defaults to {@code Object.class} (opt-out).
     */
    default Class<?> outputType() {
        return Object.class;
    }

    /**
     * Publishes additional context entries alongside the primary output.
     * <p>
     * Called by the executor after {@link #execute}. Override to write well-known
     * {@link ContextKey} constants that downstream steps can read — enabling reusable,
     * generic steps that publish metadata (token counts, confidence scores, detected
     * language) without coupling to a specific workflow or record type.
     * <p>
     * The primary output flows via input chaining; metadata flows via context keys.
     * Default: returns ctx unchanged (no additional writes).
     *
     * @param ctx    the current context (after auto-propagation of primary output)
     * @param output the primary output from {@link #execute}
     * @return the context with any additional entries (or ctx unchanged)
     */
    default AgentContext updateContext(AgentContext ctx, O output) {
        return ctx;
    }

    /**
     * Creates a named step from a lambda.
     * <p>
     * Use when a step needs a human-readable name for traces:
     * <pre>{@code
     * Step.named("fetch-diff", (ctx, prNumber) -> github.fetchDiff(prNumber))
     * }</pre>
     *
     * @param name the step name (appears in traces and errors)
     * @param fn   the function to execute
     * @param <I>  the input type
     * @param <O>  the output type
     * @return a named Step
     */
    static <I, O> Step<I, O> named(String name, BiFunction<AgentContext, I, O> fn) {
        return new Step<>() {
            @Override
            public O execute(AgentContext ctx, I input) {
                return fn.apply(ctx, input);
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String toString() {
                return "Step[" + name + "]";
            }
        };
    }

    /**
     * Returns an identity step that passes its input through as output.
     * <p>
     * Useful as a no-op placeholder in decision options or branch endpoints:
     * <pre>{@code
     * decision(client).option("done", Step.noop())
     * }</pre>
     *
     * @param <T> the input and output type
     * @return a no-op step named "noop"
     */
    @SuppressWarnings("unchecked")
    static <T> Step<T, T> noop() {
        return (Step<T, T>) NoopStep.INSTANCE;
    }

    /**
     * Returns a named identity step that passes its input through as output.
     * <p>
     * Use when a named placeholder is needed in the IR — the name appears in traces
     * and is referenceable by {@code WorkflowGraphAssert}:
     * <pre>{@code
     * .branch(__ -> config.skipAi())
     *     .then(Step.noop("skip-ai"))
     *     .otherwise(aiAssessment)
     * }</pre>
     *
     * @param name the step name — visible in the WorkflowGraph and traces
     * @param <T>  the input and output type
     * @return a named no-op step
     */
    static <T> Step<T, T> noop(String name) {
        return new NoopStep<>(name);
    }
}

/**
 * Package-private implementation for the no-op step.
 */
final class NoopStep<T> implements Step<T, T> {

    static final NoopStep<?> INSTANCE = new NoopStep<>("noop");

    private final String name;

    NoopStep(String name) {
        this.name = name;
    }

    @Override
    public T execute(AgentContext ctx, T input) {
        return input;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "Step[" + name + "]";
    }
}
