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

import io.github.markpollack.workflow.engine.OperationHandler;
import io.github.markpollack.workflow.engine.SimpleOperationRegistry;
import io.github.markpollack.workflow.engine.WorkflowEventSink;
import io.github.markpollack.workflow.engine.WorkflowInterpreter;
import io.github.markpollack.workflow.engine.WorkflowRunOutcome;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.spec.SpecEmitterOptions;
import io.github.markpollack.workflow.flows.spec.WorkflowSpecEmission;
import io.github.markpollack.workflow.flows.workflow.Workflow;

import java.util.Map;

/**
 * A real PR-review workflow authored with the v1 Java DSL, emitted to a v2
 * {@code WorkflowSpec} via {@code .toSpec()}, and executed on the v2
 * {@code WorkflowInterpreter} (Stage-4 real workflow; roadmap Step 4.1).
 *
 * <h2>Topology (sequential + one decision — honestly within the alpha subset)</h2>
 * <pre>
 * fetch_diff ──▶ analyze_diff ──▶ (branch: approved?) ──▶ approve ─┐
 *  [subprocess]   [in-process]                          └─▶ reject ─┴─▶ done
 * </pre>
 *
 * <p><b>Two operation-handler implementations in one run (DD-18)</b>: {@code fetch_diff}
 * is served by a {@link SubprocessOperationHandler} (work in a separate process), while
 * {@code analyze_diff}/{@code approve}/{@code reject}/the branch router are served by the
 * shipped in-process {@code StepOperationHandler} (auto-registered by the emitter). The
 * interpreter neither knows nor cares which is which — the seam has two real
 * implementations.
 *
 * <p><b>Where control flow lives (the R1/R3 static-spec measurement)</b>: the one
 * meaningful branch (approve vs reject) is a graph-visible decision node; the analysis
 * is a single opaque operation (the semantic-Step boundary — not hidden control flow).
 */
public final class PrReviewV2 {

    /** The stable, portable ref for the non-in-process fetch operation (DD-12 prefix is metadata). */
    public static final String FETCH_REF = "subprocess:pr.fetch-diff:v1";

    /**
     * Deterministic diff-producing script (a stand-in for {@code git diff}); the PR id
     * arrives as {@code $1} and is echoed into the diff so the downstream decision is a
     * pure function of the workflow input.
     */
    static final String DIFF_SCRIPT =
            "printf 'diff --git a/%s.java b/%s.java\\n+// change for %s\\n' \"$1\" \"$1\" \"$1\"";

    private PrReviewV2() {
    }

    /** Authors the workflow with the v1 DSL and emits it as a v2 spec. */
    public static WorkflowSpecEmission emit() {
        // fetch_diff is a placeholder in the DSL — its behavior is supplied by the
        // subprocess handler in registry(), pinned to a stable portable ref.
        Step<Object, Object> fetchDiff = Step.named("fetch_diff", (ctx, in) -> in);

        Step<Object, Object> analyzeDiff = Step.named("analyze_diff", (ctx, diff) -> {
            String text = String.valueOf(diff);
            long added = text.lines().filter(l -> l.startsWith("+")).count();
            // a "big" change is flagged for changes; anything else is approvable
            boolean approved = !text.toLowerCase().contains("big");
            return Map.of(
                    "addedLines", added,
                    "approved", approved,
                    "summary", "diff with " + added + " added line(s)");
        });

        Step<Object, Object> approve = Step.named("approve",
                (ctx, analysis) -> "APPROVED: " + field(analysis, "summary"));
        Step<Object, Object> reject = Step.named("reject",
                (ctx, analysis) -> "CHANGES_REQUESTED: " + field(analysis, "summary"));

        Workflow<String, Object> workflow = Workflow.<String, Object>define("pr-review-v2")
                .step(fetchDiff)
                .then(analyzeDiff)
                .branch(analysis -> Boolean.TRUE.equals(field(analysis, "approved")))
                    .then(approve)
                    .otherwise(reject)
                .build();

        SpecEmitterOptions options = SpecEmitterOptions.builder()
                .node("fetch_diff", n -> n.operation("fetch-diff", FETCH_REF))
                .build();
        return workflow.toSpec(options);
    }

    /**
     * Builds the operation registry: the subprocess handler for {@code fetch_diff}, the
     * emitter's in-process handlers for everything else.
     */
    public static SimpleOperationRegistry registry(WorkflowSpecEmission emission) {
        SimpleOperationRegistry registry = new SimpleOperationRegistry();
        registry.register(FETCH_REF, new SubprocessOperationHandler(DIFF_SCRIPT));
        for (Map.Entry<String, OperationHandler> entry : emission.handlersByRef().entrySet()) {
            if (!entry.getKey().equals(FETCH_REF)) {
                registry.register(entry.getKey(), entry.getValue());
            }
        }
        return registry;
    }

    /** Runs the workflow end-to-end on the v2 interpreter, publishing events to {@code sink}. */
    public static WorkflowRunOutcome run(String prId, WorkflowEventSink sink) {
        WorkflowSpecEmission emission = emit();
        return new WorkflowInterpreter(registry(emission), sink).run(emission.spec(), "pr-" + prId, prId);
    }

    @SuppressWarnings("unchecked")
    private static Object field(Object map, String key) {
        return map instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(key) : null;
    }
}
