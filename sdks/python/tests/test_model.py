"""Model construction, equality, and the SpecInvariants mirror (invalid states throw)."""

import pytest

from agent_workflow import (
    Always,
    Binding,
    DecisionNode,
    Edge,
    ErrorMatch,
    Metadata,
    OperationDeclaration,
    RetryPolicy,
    TaskNode,
    TerminateNode,
    Timeout,
    WorkflowSpec,
    load,
)


def minimal_spec() -> WorkflowSpec:
    return WorkflowSpec(
        metadata=Metadata(name="t"),
        operations={"work": OperationDeclaration(ref="java:test.work:v1")},
        nodes=(
            TaskNode(id="do", operation="work"),
            TerminateNode(id="done", status="completed"),
        ),
        edges=(Edge(from_="do", to="done", when=Always()),),
        entrypoint="do",
    )


def test_equal_construction_yields_equal_models_and_bytes() -> None:
    a, b = minimal_spec(), minimal_spec()
    assert a == b
    assert a.to_json() == b.to_json()
    assert load(a.to_json()) == a


class TestInvariants:
    def test_binding_grammar_is_enforced_at_construction(self) -> None:
        Binding(from_="$input")
        Binding(from_="$input.url")
        Binding(from_="$context.pr.diff")
        Binding(from_="$const.approval_threshold")
        Binding(from_="$node.fetch_diff.output")
        Binding(from_="$node.route.decision")
        for bad in ("$node.a.b.output", "$input.a.b", "$nope", "input", "$node.x.result"):
            with pytest.raises(ValueError):
                Binding(from_=bad)

    def test_node_ids_reject_dots(self) -> None:
        with pytest.raises(ValueError):
            TaskNode(id="a.b", operation="work")

    def test_decision_outcomes_must_be_unique_and_nonempty(self) -> None:
        with pytest.raises(ValueError):
            DecisionNode(id="d", operation="route", outcomes=())
        with pytest.raises(ValueError):
            DecisionNode(id="d", operation="route", outcomes=("x", "x"))

    def test_error_match_requires_a_criterion(self) -> None:
        with pytest.raises(ValueError):
            ErrorMatch()

    def test_retry_bounds_mirror_the_schema(self) -> None:
        with pytest.raises(ValueError):
            RetryPolicy(max_attempts=0)
        with pytest.raises(ValueError):
            RetryPolicy(max_attempts=2**31)
        with pytest.raises(ValueError):
            RetryPolicy(max_attempts=2, retry_on=())  # absent or non-empty
        with pytest.raises(ValueError):
            Timeout(per_attempt_millis=2**53)

    def test_terminate_status_is_closed(self) -> None:
        with pytest.raises(ValueError):
            TerminateNode(id="t", status="paused")  # type: ignore[arg-type]

    def test_spec_requires_operations_and_nodes(self) -> None:
        with pytest.raises(ValueError):
            WorkflowSpec(
                metadata=Metadata(name="t"),
                operations={},
                nodes=(TerminateNode(id="done", status="completed"),),
                edges=(),
                entrypoint="done",
            )


class TestStrictParsing:
    def test_duplicate_keys_are_rejected(self) -> None:
        from agent_workflow import WorkflowValidationError

        doc = '{"apiVersion": "workflow/v2alpha", "apiVersion": "workflow/v2alpha"}'
        with pytest.raises(WorkflowValidationError) as raised:
            load(doc)
        assert raised.value.errors[0].code == "SCHEMA_INVALID"
        assert "duplicate" in raised.value.errors[0].message

    def test_malformed_json_is_a_schema_invalid_error(self) -> None:
        from agent_workflow import WorkflowValidationError

        with pytest.raises(WorkflowValidationError) as raised:
            load("{not json")
        assert raised.value.errors[0].code == "SCHEMA_INVALID"

    @pytest.mark.parametrize("literal", ["NaN", "Infinity", "-Infinity"])
    def test_non_finite_literals_are_rejected_at_parse(self, literal: str) -> None:
        # not valid JSON (RFC 8259) and outside the JCS number domain: reject
        # structuredly at parse, never crash later at canonical emission
        from agent_workflow import WorkflowValidationError

        doc = f'{{"apiVersion": "workflow/v2alpha", "constants": {{"x": {literal}}}}}'
        with pytest.raises(WorkflowValidationError) as raised:
            load(doc)
        assert raised.value.errors[0].code == "SCHEMA_INVALID"
        assert "non-finite" in raised.value.errors[0].message
