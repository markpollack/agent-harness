"""Author the golden pr-review workflow and emit its canonical WorkflowSpec JSON.

This is the complete authoring story: build declaratively, validate at ``build()``,
emit canonical bytes, hand off to the JVM engine (the SDK never executes anything).
Run it with::

    uv run python examples/golden_pr_review.py > pr-review.workflow.json

The emitted bytes are canonically byte-equal to the Conformance Kit's golden fixture
— and to what the Java DSL's ``.toSpec()`` emits for the same workflow. One IR,
three authoring surfaces.
"""

import sys

from agent_workflow import (
    WorkflowBuilder,
    from_const,
    from_context,
    from_input,
    from_node_output,
)


def build_pr_review():  # type: ignore[no-untyped-def]
    wf = WorkflowBuilder("pr-review", version="1.0.0")
    wf.constant("approval_threshold", 0.8)

    wf.operation("fetch-pr-diff", ref="java:github.fetch_pr_diff:v1")
    wf.operation("analyze-diff", ref="python:review.analyze_diff:v2")
    wf.operation("route-review", ref="java:review.route:v1")
    wf.operation("post-review", ref="typescript:github.post_review:v1")

    wf.task(
        "fetch_diff",
        operation="fetch-pr-diff",
        input={"url": from_input("url")},
        context_writes={"pr.diff": from_node_output("fetch_diff")},
    )
    wf.task(
        "analyze_diff",
        operation="analyze-diff",
        input={"diff": from_context("pr.diff")},
    )
    wf.decision(
        "route",
        operation="route-review",
        input={
            "analysis": from_node_output("analyze_diff"),
            "threshold": from_const("approval_threshold"),
        },
        outcomes=["post", "fail"],
    )
    wf.task(
        "post_comment",
        operation="post-review",
        input={"review": from_node_output("analyze_diff")},
    )
    wf.terminate("done", status="completed", result=from_node_output("post_comment"))
    wf.terminate("rejected", status="failed", result=from_node_output("analyze_diff"))

    wf.edge("fetch_diff", "analyze_diff")
    wf.edge("analyze_diff", "route")
    wf.edge("route", "post_comment", outcome="post")
    wf.edge("route", "rejected", outcome="fail")
    wf.edge("post_comment", "done")

    return wf.build(
        entrypoint="fetch_diff",
        outputs={"result": from_node_output("post_comment")},
    )


if __name__ == "__main__":
    sys.stdout.buffer.write(build_pr_review().to_json())
