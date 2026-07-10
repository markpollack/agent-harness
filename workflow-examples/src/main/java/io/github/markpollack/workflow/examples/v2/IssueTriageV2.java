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

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.engine.WorkflowEventSink;
import io.github.markpollack.workflow.engine.WorkflowInterpreter;
import io.github.markpollack.workflow.engine.WorkflowRunOutcome;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.spec.WorkflowSpecEmission;
import io.github.markpollack.workflow.flows.workflow.Workflow;

import java.util.Map;

/**
 * An issue-triage workflow whose operations are all in-process, so the SAME workflow
 * runs on both the v1 DSL path ({@code .run()}) and the v2 path ({@code .toSpec()} →
 * interpreter) — the v1↔v2 equivalence check (roadmap Step 4.1; VISION success
 * criterion 5's DSL side).
 *
 * <h2>Topology</h2>
 * <pre>
 * intake ──▶ (branch: is it a bug?) ──▶ file_bug ─┐
 *                                       └▶ acknowledge ─┴─▶ done
 * </pre>
 *
 * <p>The classification branch is graph-visible; the intake work is one operation.
 */
public final class IssueTriageV2 {

    private IssueTriageV2() {
    }

    /** Builds the DSL workflow (usable on both v1 and v2 paths). */
    public static Workflow<String, Object> workflow() {
        Step<Object, Object> intake = Step.named("intake",
                (ctx, issue) -> Map.of("text", String.valueOf(issue), "kind", classify(String.valueOf(issue))));

        Step<Object, Object> fileBug = Step.named("file_bug",
                (ctx, report) -> "FILED BUG: " + field(report, "text"));
        Step<Object, Object> acknowledge = Step.named("acknowledge",
                (ctx, report) -> "ACKNOWLEDGED: " + field(report, "text"));

        return Workflow.<String, Object>define("issue-triage-v2")
                .step(intake)
                .branch(report -> "bug".equals(field(report, "kind")))
                    .then(fileBug)
                    .otherwise(acknowledge)
                .build();
    }

    /** v1 path: run the DSL directly (the built workflow executes as a Step). */
    public static Object runV1(String issue) {
        return workflow().execute(AgentContext.create(), issue);
    }

    /** v2 path: emit to a spec and run on the interpreter. */
    public static WorkflowRunOutcome runV2(String issue, WorkflowEventSink sink) {
        WorkflowSpecEmission emission = workflow().toSpec();
        return new WorkflowInterpreter(emission.registerInto(
                new io.github.markpollack.workflow.engine.SimpleOperationRegistry()), sink)
                .run(emission.spec(), "issue-" + issue.hashCode(), issue);
    }

    static String classify(String issue) {
        String lower = issue.toLowerCase();
        return (lower.contains("crash") || lower.contains("error") || lower.contains("bug")
                || lower.contains("fail")) ? "bug" : "other";
    }

    @SuppressWarnings("unchecked")
    private static Object field(Object map, String key) {
        return map instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(key) : null;
    }
}
