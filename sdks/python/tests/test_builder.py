"""Builder API (P2.1): authoring the golden workflow + build-time rejections."""

import pytest

from agent_workflow import (
    Backoff,
    ErrorMatch,
    RetryPolicy,
    Timeout,
    WorkflowBuilder,
    WorkflowSpec,
    WorkflowValidationError,
    from_const,
    from_context,
    from_input,
    from_node_output,
    load,
    validate,
)


def golden_workflow() -> WorkflowSpec:
    """The golden pr-review workflow, authored per the P1.0 sketch."""
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
    wf.task("analyze_diff", operation="analyze-diff", input={"diff": from_context("pr.diff")})
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
        "post_comment", operation="post-review", input={"review": from_node_output("analyze_diff")}
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


def test_golden_workflow_builds_and_passes_both_phases() -> None:
    spec = golden_workflow()
    assert validate(spec) == []
    assert load(spec.to_json()) == spec  # written form passes the reader's phases too


def test_never_touched_sections_are_absent_not_empty() -> None:
    wf = WorkflowBuilder("lean")
    wf.operation("op", ref="java:t.x:v1")
    wf.task("a", operation="op")
    wf.terminate("z", status="completed")
    wf.edge("a", "z")
    spec = wf.build(entrypoint="a")

    wire = spec.to_wire()
    for section in ("types", "constants", "contextSchema", "policies", "outputs"):
        assert section not in wire, f"{section} must be absent, not empty (rule 4)"


def test_policies_and_error_edges_author_correctly() -> None:
    wf = WorkflowBuilder("resilient")
    wf.operation("fetch", ref="java:t.fetch:v1")
    wf.operation("fallback", ref="java:t.fallback:v1")
    wf.task(
        "flaky",
        operation="fetch",
        retry=RetryPolicy(
            max_attempts=3,
            backoff=Backoff.exponential(initial_millis=500, multiplier=2.0, max_millis=10_000),
        ),
        timeout=Timeout(per_attempt_millis=30_000),
    )
    wf.task("recover", operation="fallback")
    wf.terminate("done", status="completed")
    wf.edge("flaky", "done")
    wf.edge("flaky", "recover", error=ErrorMatch(code="UPSTREAM_TIMEOUT"))
    wf.edge("recover", "done")
    spec = wf.build(entrypoint="flaky")

    assert load(spec.to_json()) == spec


class TestBuildTimeRejections:
    """Each semantic violation representable through the API is caught at build()."""

    @staticmethod
    def base() -> WorkflowBuilder:
        wf = WorkflowBuilder("t")
        wf.operation("op", ref="java:t.x:v1")
        return wf

    def expect_codes(self, wf: WorkflowBuilder, entrypoint: str, *codes: str) -> None:
        with pytest.raises(WorkflowValidationError) as raised:
            wf.build(entrypoint=entrypoint)
        assert {e.code for e in raised.value.errors} == set(codes)

    def test_duplicate_node_id(self) -> None:
        wf = self.base()
        wf.task("a", operation="op")
        wf.task("a", operation="op")
        wf.terminate("z", status="completed")
        wf.edge("a", "z")
        self.expect_codes(wf, "a", "DUPLICATE_NODE_ID")

    def test_unknown_operation(self) -> None:
        wf = self.base()
        wf.task("a", operation="nope")
        wf.terminate("z", status="completed")
        wf.edge("a", "z")
        self.expect_codes(wf, "a", "UNKNOWN_OPERATION")

    def test_edge_unknown_node_and_unreachable(self) -> None:
        wf = self.base()
        wf.task("a", operation="op")
        wf.terminate("z", status="completed")
        wf.edge("a", "ghost")
        self.expect_codes(wf, "a", "EDGE_UNKNOWN_NODE", "UNREACHABLE_NODE")

    def test_undeclared_outcome_and_unmatched(self) -> None:
        wf = self.base()
        wf.decision("d", operation="op", outcomes=["yes"])
        wf.task("a", operation="op")
        wf.terminate("z", status="completed")
        wf.edge("d", "a", outcome="nope")  # undeclared; 'yes' unmatched
        wf.edge("a", "z")
        self.expect_codes(wf, "d", "UNDECLARED_OUTCOME", "UNMATCHED_OUTCOME")

    def test_terminate_with_outgoing_edge(self) -> None:
        wf = self.base()
        wf.task("a", operation="op")
        wf.terminate("z", status="completed")
        wf.edge("a", "z")
        wf.edge("z", "a")
        self.expect_codes(wf, "a", "TERMINATE_WITH_OUTGOING_EDGE", "GRAPH_CYCLE")

    def test_graph_cycle(self) -> None:
        wf = self.base()
        wf.task("a", operation="op")
        wf.task("b", operation="op")
        wf.edge("a", "b")
        wf.edge("b", "a")
        self.expect_codes(wf, "a", "GRAPH_CYCLE")

    def test_unknown_entrypoint(self) -> None:
        wf = self.base()
        wf.task("a", operation="op")
        wf.terminate("z", status="completed")
        wf.edge("a", "z")
        self.expect_codes(wf, "ghost", "UNKNOWN_ENTRYPOINT")

    def test_binding_unknown_node(self) -> None:
        wf = self.base()
        wf.task("a", operation="op", input={"x": from_node_output("ghost")})
        wf.terminate("z", status="completed")
        wf.edge("a", "z")
        self.expect_codes(wf, "a", "BINDING_UNKNOWN_NODE")

    def test_invalid_backoff_is_validator_only_not_constructor(self) -> None:
        # SEM-13 stays validator-only: constructing the policy succeeds...
        policy = RetryPolicy(
            max_attempts=2, backoff=Backoff(strategy="exponential", initial_millis=10)
        )
        wf = self.base()
        wf.task("a", operation="op", retry=policy)
        wf.terminate("z", status="completed")
        wf.edge("a", "z")
        # ...and build() reports it
        self.expect_codes(wf, "a", "INVALID_BACKOFF")

    def test_call_site_guards(self) -> None:
        wf = self.base()
        with pytest.raises(ValueError):
            wf.edge("a", "b", outcome="x", error=ErrorMatch(code="Y"))
        with pytest.raises(ValueError):
            wf.operation("op", ref="java:dup:v1")  # duplicate alias
        wf.constant("c", 1)
        with pytest.raises(ValueError):
            wf.constant("c", 2)  # duplicate constant
        with pytest.raises(ValueError):
            from_node_output("dotted.id")  # frozen grammar at the helper
