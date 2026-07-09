"""Cross-SDK equivalence (P2.2): builder-authored workflows are canonically
byte-equal to the shared fixtures — the same fixture core Step 3.1's Java emitter
and TS Step T2.2 compare against. This is DD-10's parity definition made executable.
"""

import json

import rfc8785

from agent_workflow import (
    Backoff,
    ErrorMatch,
    ErrorMatcher,
    RetryPolicy,
    Timeout,
    WorkflowBuilder,
    WorkflowSpec,
    from_const,
    from_context,
    from_input,
    from_node_output,
)
from conftest import SPEC_DIR
from test_builder import golden_workflow


def assert_byte_equal(spec: WorkflowSpec, fixture_name: str) -> None:
    fixture = (SPEC_DIR / "fixtures" / "valid" / f"{fixture_name}.json").read_bytes()
    expected = rfc8785.dumps(json.loads(fixture.decode("utf-8")))
    assert spec.to_json() == expected, f"builder output diverges from {fixture_name}"


def test_golden_pr_review_byte_equality() -> None:
    """THE cross-SDK equivalence criterion (P2.2 exit; SDK gate for T2.2 parity)."""
    assert_byte_equal(golden_workflow(), "golden-pr-review")


def test_minimal_one_task() -> None:
    wf = WorkflowBuilder("minimal-one-task", version="1.0.0")
    wf.operation("work", ref="java:test.work:v1")
    wf.task("do", operation="work")
    wf.terminate("done", status="completed", result=from_node_output("do"))
    wf.edge("do", "done")
    assert_byte_equal(wf.build(entrypoint="do"), "minimal-one-task")


def test_decision_fan_out() -> None:
    wf = WorkflowBuilder("decision-fan-out", version="1.0.0")
    wf.operation("work", ref="java:test.work:v1")
    wf.operation("route", ref="java:test.route:v1")
    wf.task("prepare", operation="work")
    wf.decision(
        "route_work",
        operation="route",
        outcomes=["small", "medium", "large"],
        input={"item": from_node_output("prepare")},
    )
    wf.task("handle_small", operation="work")
    wf.task("handle_medium", operation="work")
    wf.task("handle_large", operation="work")
    wf.terminate("done", status="completed")
    wf.edge("prepare", "route_work")
    wf.edge("route_work", "handle_small", outcome="small")
    wf.edge("route_work", "handle_medium", outcome="medium")
    wf.edge("route_work", "handle_large", outcome="large")
    wf.edge("handle_small", "done")
    wf.edge("handle_medium", "done")
    wf.edge("handle_large", "done")
    assert_byte_equal(wf.build(entrypoint="prepare"), "decision-fan-out")


def test_context_writes() -> None:
    wf = WorkflowBuilder("context-writes", version="1.0.0")
    wf.operation("work", ref="java:test.work:v1")
    wf.operation("route", ref="java:test.route:v1")
    wf.task(
        "fetch",
        operation="work",
        input={"url": from_input("url")},
        context_writes={
            "doc.body": from_node_output("fetch"),
            "doc.source": from_input("url"),
        },
    )
    wf.task("summarize", operation="work", input={"body": from_context("doc.body")})
    wf.terminate("done", status="completed", result=from_node_output("summarize"))
    wf.edge("fetch", "summarize")
    wf.edge("summarize", "done")
    assert_byte_equal(wf.build(entrypoint="fetch"), "context-writes")


def test_constants_outputs() -> None:
    wf = WorkflowBuilder("constants-outputs", version="1.0.0")
    wf.constant("threshold", 0.75)
    wf.constant("weights", {"clarity": 2, "accuracy": 3})
    wf.constant("labels", ["a", "b"])
    wf.operation("work", ref="java:test.work:v1")
    wf.operation("route", ref="java:test.route:v1")
    wf.task(
        "score",
        operation="work",
        input={"threshold": from_const("threshold"), "weights": from_const("weights")},
    )
    wf.terminate("done", status="completed", result=from_node_output("score"))
    wf.edge("score", "done")
    spec = wf.build(
        entrypoint="score",
        outputs={
            "score": from_node_output("score"),
            "threshold_used": from_const("threshold"),
        },
    )
    assert_byte_equal(spec, "constants-outputs")


def test_error_edge_routing() -> None:
    wf = WorkflowBuilder("error-edge-routing", version="1.0.0")
    wf.operation("work", ref="java:test.work:v1")
    wf.operation("route", ref="java:test.route:v1")
    wf.task(
        "call_api",
        operation="work",
        retry=RetryPolicy(
            max_attempts=3,
            backoff=Backoff.exponential(initial_millis=500, multiplier=2.0, max_millis=10_000),
            retry_on=(ErrorMatcher(code="RATE_LIMIT"),),
        ),
        timeout=Timeout(per_attempt_millis=60_000),
    )
    wf.task("fallback", operation="work")
    wf.terminate("done", status="completed")
    wf.terminate("failed", status="failed")
    wf.edge("call_api", "done")
    wf.edge("call_api", "fallback", error=ErrorMatch(code="RATE_LIMIT"))
    wf.edge("fallback", "failed")
    wf.policies(retry=RetryPolicy(max_attempts=1))
    assert_byte_equal(wf.build(entrypoint="call_api"), "error-edge-routing")


def test_annotations_lossless_authoring() -> None:
    wf = WorkflowBuilder(
        "annotations",
        version="1.0.0",
        labels={"team": "platform"},
        annotations={
            "editor.example.io/theme": "dark",
            "docs.example.io/owner": "mark",
        },
    )
    wf.operation("work", ref="java:test.work:v1")
    wf.operation("route", ref="java:test.route:v1")
    wf.task(
        "styled",
        operation="work",
        annotations={
            "editor.example.io/position": '{"x":120,"y":80}',
            "editor.example.io/collapsed": "false",
        },
    )
    wf.decision(
        "route",
        operation="route",
        outcomes=["finish"],
        annotations={"editor.example.io/position": '{"x":220,"y":80}'},
    )
    wf.terminate(
        "done",
        status="completed",
        annotations={"editor.example.io/position": '{"x":320,"y":80}'},
    )
    wf.edge("styled", "route")
    wf.edge("route", "done", outcome="finish")
    assert_byte_equal(wf.build(entrypoint="styled"), "annotations")


def test_integer_forms_normalize_identically() -> None:
    # the fixture uses 3.0/1000.0 forms; JCS normalizes — authoring with plain ints
    # must land on the same canonical bytes
    wf = WorkflowBuilder("integer-forms", version="1.0.0")
    wf.operation("work", ref="java:test.work:v1")
    wf.task(
        "a",
        operation="work",
        retry=RetryPolicy(max_attempts=3, backoff=Backoff.fixed(initial_millis=1000)),
    )
    wf.terminate("end", status="completed")
    wf.edge("a", "end")
    assert_byte_equal(wf.build(entrypoint="a"), "integer-forms")
