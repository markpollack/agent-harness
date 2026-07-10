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
package io.github.markpollack.workflow.examples.v2;

import io.github.markpollack.workflow.engine.InMemoryEventSink;
import io.github.markpollack.workflow.engine.WorkflowRunOutcome;
import io.github.markpollack.workflow.flows.spec.WorkflowSpecEmission;

/**
 * Runnable demonstration of the full v2 path: author with the DSL → emit the
 * language-neutral {@code WorkflowSpec} via {@code .toSpec()} → execute on the
 * interpreter → read the canonical event trace.
 *
 * <pre>{@code ./mvnw -q -pl workflow-examples exec:java \
 *   -Dexec.mainClass=io.github.markpollack.workflow.examples.v2.V2Demo -Dexec.args=feature-42}</pre>
 */
public final class V2Demo {

    private V2Demo() {
    }

    public static void main(String[] args) {
        String prId = args.length > 0 ? args[0] : "feature-42";

        WorkflowSpecEmission emission = PrReviewV2.emit();
        System.out.println("=== Emitted WorkflowSpec (nodes) ===");
        emission.spec().nodes().forEach(n -> System.out.println("  " + n.id() + "  [" + n.getClass()
                .getSimpleName().replace("SpecNode", "").toLowerCase() + "]"));

        InMemoryEventSink sink = new InMemoryEventSink();
        WorkflowRunOutcome outcome = PrReviewV2.run(prId, sink);

        System.out.println("\n=== Event trace ===");
        sink.events().forEach(e -> System.out.printf("  %2d  %-22s %s%n",
                e.sequence(), e.eventType().wireName(), e.nodeId() == null ? "" : e.nodeId()));

        System.out.println("\n=== Outcome ===");
        System.out.println("  " + outcome.terminalState() + ": " + outcome.result());
    }
}
