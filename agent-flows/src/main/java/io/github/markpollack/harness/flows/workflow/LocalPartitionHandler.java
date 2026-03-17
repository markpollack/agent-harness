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
package io.github.markpollack.harness.flows.workflow;

import io.github.markpollack.harness.flows.AgentContext;
import io.github.markpollack.harness.flows.Step;

/**
 * Default {@link PartitionHandler} that executes steps directly in-process.
 * <p>
 * No retry, no checkpoint, no overhead. This is the right handler for
 * development, testing, and pipelines that don't need crash recovery.
 */
public final class LocalPartitionHandler implements PartitionHandler {

    @Override
    public <I, O> O execute(Step<I, O> step, AgentContext ctx, I input) {
        return step.execute(ctx, input);
    }
}
