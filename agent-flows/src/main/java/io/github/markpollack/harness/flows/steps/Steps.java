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
package io.github.markpollack.harness.flows.steps;

import io.github.markpollack.harness.flows.AgentContext;
import io.github.markpollack.harness.flows.ContextKey;
import io.github.markpollack.harness.flows.Step;
import io.github.markpollack.harness.flows.workflow.WorkflowStatus;
import io.github.markpollack.harness.flows.workflow.WorkflowTerminatedException;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Factory for creating plain Java {@link Step} instances — no LLM involved.
 * <p>
 * This is the workhorse for deterministic steps: API calls, test runners,
 * file I/O, data fetching, posting results. The lambda receives the
 * {@link AgentContext} and the step input, and returns the step output.
 *
 * <pre>{@code
 * Steps.of((ctx, event) -> github.fetchPRDiff(event.prNumber()))
 * Steps.of((ctx, result) -> testRunner.run())
 * Steps.of((ctx, input) -> db.lookup(input))
 * }</pre>
 *
 * When context is not needed, use the single-argument overload:
 * <pre>{@code
 * Steps.of(input -> processData(input))
 * }</pre>
 *
 * For named steps that appear in traces, use {@link Step#named(String, BiFunction)}.
 */
public final class Steps {

    private Steps() {
    }

    /**
     * Creates a {@link Step} from a {@link BiFunction} that receives both
     * the context and the input.
     *
     * @param fn  the function to wrap
     * @param <I> the input type
     * @param <O> the output type
     * @return a Step backed by the given function
     */
    public static <I, O> Step<I, O> of(BiFunction<AgentContext, I, O> fn) {
        return fn::apply;
    }

    /**
     * Creates a {@link Step} from a {@link Function} that receives only the input.
     * <p>
     * Use when the step logic does not need access to the {@link AgentContext}.
     *
     * @param fn  the function to wrap
     * @param <I> the input type
     * @param <O> the output type
     * @return a Step that ignores context and delegates to the given function
     */
    public static <I, O> Step<I, O> of(Function<I, O> fn) {
        return (ctx, input) -> fn.apply(input);
    }

    /**
     * Creates a step that terminates the workflow with the given status and message.
     * <p>
     * Usable inside branch cases, error paths, and default cases.
     * The step is a named graph node visible in traces and visualization.
     *
     * <pre>{@code
     * .branch(output -> isValid(output))
     *     .then(commit)
     *     .otherwise(Steps.terminate(WorkflowStatus.FAILED, "Validation failed"))
     * }</pre>
     *
     * @param status  the terminal status
     * @param message human-readable reason for termination
     * @param <I>     input type (accepts any input)
     * @param <O>     output type (never reached — throws)
     * @return a Step that terminates the workflow
     */
    public static <I, O> Step<I, O> terminate(WorkflowStatus status, String message) {
        return Step.named("terminate[" + status + "]", (ctx, input) -> {
            throw new WorkflowTerminatedException(status, message);
        });
    }

    /**
     * Returns a {@link ContextKey} for accessing a prior step's output from {@link AgentContext}.
     * <p>
     * The {@code WorkflowExecutor} auto-propagates every step's output into context under
     * this key after execution. Any downstream step can read any prior step's result:
     * <pre>{@code
     * ReviewReport prior = (ReviewReport) ctx.require(Steps.outputOf("analyzeDiff"));
     * }</pre>
     *
     * @param stepName the name of the step whose output to access
     * @return a ContextKey for the step's output
     */
    public static ContextKey<Object> outputOf(String stepName) {
        return ContextKey.of("step.output." + stepName, Object.class);
    }

    /**
     * Wraps a step to retry on any exception, up to {@code maxAttempts} times total.
     * If all attempts fail, the last exception is wrapped and propagated.
     * <p>
     * This covers the simple 80% re-run case. For exponential backoff, circuit breaking,
     * or result-based retry, use Resilience4j directly.
     * <p>
     * <b>Do not use with {@code TemporalStepRunner}</b> — Temporal's {@code RetryPolicy}
     * and this wrapper would multiply retry attempts.
     *
     * <pre>{@code
     * Steps.retrying(3, myStep)   // retry myStep up to 3 times on any exception
     * }</pre>
     *
     * @param maxAttempts maximum number of execution attempts (must be >= 1)
     * @param step        the step to wrap
     * @param <I>         input type
     * @param <O>         output type
     * @return a step that retries the given step on failure
     */
    public static <I, O> Step<I, O> retrying(int maxAttempts, Step<I, O> step) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        return Step.named("retrying[" + maxAttempts + "]:" + step.name(), (ctx, input) -> {
            Exception last = null;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                try {
                    return step.execute(ctx, input);
                }
                catch (Exception e) {
                    last = e;
                }
            }
            throw new RuntimeException(
                    "Step '" + step.name() + "' failed after " + maxAttempts + " attempts", last);
        });
    }

    /**
     * Wraps a step to retry only on a specific exception type, up to {@code maxAttempts}
     * times total. Exceptions not matching {@code exType} propagate immediately without
     * consuming a retry attempt.
     * <p>
     * <b>Do not use with {@code TemporalStepRunner}</b> — Temporal's {@code RetryPolicy}
     * and this wrapper would multiply retry attempts.
     *
     * <pre>{@code
     * Steps.retrying(3, TimeoutException.class, myStep)
     * }</pre>
     *
     * @param maxAttempts maximum number of execution attempts (must be >= 1)
     * @param exType      the exception type to retry on
     * @param step        the step to wrap
     * @param <I>         input type
     * @param <O>         output type
     * @param <E>         exception type
     * @return a step that retries the given step on matching exceptions
     */
    public static <I, O, E extends Exception> Step<I, O> retrying(
            int maxAttempts, Class<E> exType, Step<I, O> step) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        return Step.named(
                "retrying[" + maxAttempts + "," + exType.getSimpleName() + "]:" + step.name(),
                (ctx, input) -> {
                    Exception last = null;
                    for (int attempt = 0; attempt < maxAttempts; attempt++) {
                        try {
                            return step.execute(ctx, input);
                        }
                        catch (Exception e) {
                            if (!exType.isInstance(e)) {
                                throw e;
                            }
                            last = e;
                        }
                    }
                    throw new RuntimeException(
                            "Step '" + step.name() + "' failed after " + maxAttempts + " attempts",
                            last);
                });
    }
}
