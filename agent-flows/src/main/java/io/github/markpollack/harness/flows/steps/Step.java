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
import io.github.markpollack.harness.flows.AgentStep;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Factory for creating plain Java {@link AgentStep} instances — no LLM involved.
 * <p>
 * This is the workhorse for deterministic steps: API calls, test runners,
 * file I/O, data fetching, posting results. The lambda receives the
 * {@link AgentContext} and the step input, and returns the step output.
 *
 * <pre>{@code
 * Step.of((ctx, event) -> github.fetchPRDiff(event.prNumber()))
 * Step.of((ctx, result) -> testRunner.run())
 * Step.of((ctx, input) -> db.lookup(input))
 * }</pre>
 *
 * When context is not needed, use the single-argument overload:
 * <pre>{@code
 * Step.of(input -> processData(input))
 * }</pre>
 */
public final class Step {

    private Step() {
    }

    /**
     * Creates an {@link AgentStep} from a {@link BiFunction} that receives both
     * the context and the input.
     *
     * @param fn  the function to wrap
     * @param <I> the input type
     * @param <O> the output type
     * @return an AgentStep backed by the given function
     */
    public static <I, O> AgentStep<I, O> of(BiFunction<AgentContext, I, O> fn) {
        return fn::apply;
    }

    /**
     * Creates an {@link AgentStep} from a {@link Function} that receives only the input.
     * <p>
     * Use when the step logic does not need access to the {@link AgentContext}.
     *
     * @param fn  the function to wrap
     * @param <I> the input type
     * @param <O> the output type
     * @return an AgentStep that ignores context and delegates to the given function
     */
    public static <I, O> AgentStep<I, O> of(Function<I, O> fn) {
        return (ctx, input) -> fn.apply(input);
    }
}
