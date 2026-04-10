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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Combinators for composing {@link Step} instances into workflows.
 * <p>
 * {@code AgentFlow} provides three primitives:
 * <ul>
 *   <li>{@link #sequential} — run steps in order; each step's output feeds the next</li>
 *   <li>{@link #parallel} — run steps concurrently; collect all results</li>
 *   <li>{@link #loop} — repeat a step until a condition is met or max iterations exceeded</li>
 * </ul>
 * <p>
 * {@code AgentFlow} itself implements {@link Step}, so flows compose freely:
 * <pre>{@code
 * AgentFlow.sequential(
 *     AgentFlow.parallel(fetchDiff, fetchCiStatus),     // parallel fetch
 *     ClaudeStep.of("review this: {input}"),            // then Claude reviews
 *     Steps.of((ctx, review) -> github.postComment(review))
 * )
 * }</pre>
 *
 * <h2>Implementation note</h2>
 * <p>
 * The implementation is intentionally trivial: {@code sequential} is a for-loop,
 * {@code parallel} is {@code CompletableFuture.allOf}, {@code loop} is a while-loop
 * with a predicate. There is no new execution engine.
 *
 * <h2>Type safety in sequential</h2>
 * <p>
 * {@link #sequential(Step[])} uses unchecked casts internally to allow chaining
 * steps with heterogeneous types. The type parameters {@code I} and {@code O} correspond
 * to the first step's input and last step's output. Intermediate types are not enforced
 * at compile time — callers are responsible for step chain compatibility.
 */
public abstract class AgentFlow<I, O> implements Step<I, O> {

    // -------------------------------------------------------------------------
    // Combinators
    // -------------------------------------------------------------------------

    /**
     * Runs steps in order; each step's output is passed as input to the next step.
     * <p>
     * The type parameters {@code I} and {@code O} correspond to the first step's input
     * type and the last step's output type. Intermediate types are unchecked — callers
     * are responsible for ensuring consecutive steps have compatible types.
     *
     * @param steps the steps to run in sequence (at least one)
     * @param <I>   input type of the first step
     * @param <O>   output type of the last step
     * @return an AgentFlow that runs the steps sequentially
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @SafeVarargs
    public static <I, O> AgentFlow<I, O> sequential(Step<?, ?>... steps) {
        if (steps.length == 0) {
            throw new IllegalArgumentException("sequential requires at least one step");
        }
        List<Step<?, ?>> stepList = List.of(steps);
        return new AgentFlow<>() {
            @Override
            public O execute(AgentContext ctx, I input) {
                Object current = input;
                for (Step step : stepList) {
                    current = step.execute(ctx, current);
                }
                return (O) current;
            }
        };
    }

    /**
     * Runs steps concurrently using {@code CompletableFuture.allOf}; collects all results
     * into a {@link List}.
     * <p>
     * Each step receives the same input. All steps must accept type {@code I} and produce
     * type {@code O}. Use {@link ParallelFlow#aggregate(Function)} to reduce results to
     * a single value before passing to the next step in a sequence.
     *
     * @param steps the steps to run in parallel (at least one)
     * @param <I>   the shared input type
     * @param <O>   the per-step output type (results collected into a List)
     * @return a ParallelFlow that runs the steps concurrently
     */
    @SafeVarargs
    public static <I, O> ParallelFlow<I, O> parallel(Step<I, O>... steps) {
        if (steps.length == 0) {
            throw new IllegalArgumentException("parallel requires at least one step");
        }
        return new ParallelFlow<>(List.of(steps));
    }

    /**
     * Repeats {@code step} until {@code until} returns {@code true} or {@code max}
     * iterations are exhausted.
     * <p>
     * On each iteration, the output of the previous iteration is passed as the input to
     * the next. The first iteration receives the original {@code input} argument. If
     * the condition is not satisfied within {@code max} iterations,
     * {@link MaxIterationsExceededException} is thrown.
     * <p>
     * Use {@link #until(Predicate)} and {@link #maxIterations(int)} as static imports
     * for idiomatic usage:
     * <pre>{@code
     * AgentFlow.loop(step, until(result -> result.allTestsPass()), maxIterations(5))
     * }</pre>
     *
     * @param step  the step (or flow) to repeat
     * @param until the condition that signals successful completion; loop stops when true
     * @param max   the maximum number of iterations before throwing
     * @param <T>   the step's input and output type (must be the same for looping)
     * @return an AgentFlow that loops the step
     * @throws MaxIterationsExceededException if the condition is not met within the limit
     */
    public static <T> AgentFlow<T, T> loop(Step<T, T> step, Predicate<T> until, MaxIterations max) {
        return new AgentFlow<>() {
            @Override
            public T execute(AgentContext ctx, T input) {
                T current = input;
                for (int i = 0; i < max.value(); i++) {
                    current = step.execute(ctx, current);
                    if (until.test(current)) {
                        return current;
                    }
                }
                throw new MaxIterationsExceededException(max.value());
            }
        };
    }

    // -------------------------------------------------------------------------
    // Static helpers for idiomatic loop usage
    // -------------------------------------------------------------------------

    /**
     * Returns the predicate unchanged; intended for static import to improve readability:
     * <pre>{@code until(result -> result.isComplete())}</pre>
     *
     * @param condition the loop termination predicate (returns true to stop)
     * @param <T>       the result type
     * @return the condition predicate
     */
    public static <T> Predicate<T> until(Predicate<T> condition) {
        return condition;
    }

    /**
     * Creates a {@link MaxIterations} bound for use with {@link #loop}.
     *
     * @param n the maximum number of loop iterations (must be &gt; 0)
     * @return a MaxIterations record
     */
    public static MaxIterations maxIterations(int n) {
        return new MaxIterations(n);
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Maximum iterations limit for {@link AgentFlow#loop}.
     *
     * @param value the maximum number of iterations (must be &gt; 0)
     */
    public record MaxIterations(int value) {
        public MaxIterations {
            if (value <= 0) {
                throw new IllegalArgumentException("maxIterations must be > 0, got: " + value);
            }
        }
    }

    /**
     * An {@link AgentFlow} variant returned by {@link AgentFlow#parallel}.
     * <p>
     * Executes all steps concurrently and collects results into a {@link List}.
     * Use {@link #aggregate(Function)} to reduce to a single value before passing
     * to the next step in a {@link AgentFlow#sequential} chain.
     *
     * @param <I> the shared input type for all parallel steps
     * @param <O> the per-step output type
     */
    public static final class ParallelFlow<I, O> extends AgentFlow<I, List<O>> {

        private final List<Step<I, O>> steps;

        private ParallelFlow(List<Step<I, O>> steps) {
            this.steps = steps;
        }

        /**
         * Returns an {@link AgentFlow} that runs the parallel steps and aggregates results.
         * <p>
         * The aggregator function receives the list of all per-step outputs and produces
         * a single value passed to the next step:
         * <pre>{@code
         * AgentFlow.parallel(fetchDiff, fetchCiStatus)
         *     .aggregate(results -> String.join("\n", results))
         * }</pre>
         *
         * @param fn  the aggregation function
         * @param <R> the aggregated output type
         * @return an AgentFlow that runs parallel steps and reduces their outputs
         */
        public <R> AgentFlow<I, R> aggregate(Function<List<O>, R> fn) {
            ParallelFlow<I, O> self = this;
            return new AgentFlow<>() {
                @Override
                public R execute(AgentContext ctx, I input) {
                    return fn.apply(self.execute(ctx, input));
                }
            };
        }

        @Override
        public List<O> execute(AgentContext ctx, I input) {
            List<CompletableFuture<O>> futures = steps.stream()
                    .map(step -> CompletableFuture.supplyAsync(() -> step.execute(ctx, input)))
                    .collect(Collectors.toList());
            return futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toUnmodifiableList());
        }
    }
}
