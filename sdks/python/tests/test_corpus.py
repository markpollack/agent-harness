"""Corpus conformance (DD-9/DD-13): the Conformance Kit is the executable contract.

Every valid fixture passes both phases; every invalid fixture reproduces its
sidecar-pinned codes (``errorCodes`` = exact distinct set; ``anyOfCodes`` =
non-empty subset).
"""

import json

import pytest

from agent_workflow import WorkflowValidationError, load
from agent_workflow._io import parse_strict
from agent_workflow._validation import validate_parsed
from conftest import INVALID_FIXTURES, SPEC_DIR, VALID_FIXTURES


def collected_codes(raw: bytes) -> set[str]:
    """The distinct error codes the SDK reports for a document (parse or validation)."""
    try:
        parsed = parse_strict(raw)
    except WorkflowValidationError as ex:
        return {error.code for error in ex.errors}
    return {error.code for error in validate_parsed(parsed)}


@pytest.mark.parametrize("fixture", VALID_FIXTURES, ids=lambda p: p.stem)
def test_valid_fixture_passes_both_phases(fixture) -> None:  # type: ignore[no-untyped-def]
    assert collected_codes(fixture.read_bytes()) == set()
    load(fixture.read_bytes())  # and binds


@pytest.mark.parametrize("fixture", INVALID_FIXTURES, ids=lambda p: p.stem)
def test_invalid_fixture_reproduces_pinned_codes(fixture) -> None:  # type: ignore[no-untyped-def]
    sidecar = json.loads(
        fixture.with_name(fixture.stem + ".expected.json").read_text(encoding="utf-8")
    )
    codes = collected_codes(fixture.read_bytes())

    if "errorCodes" in sidecar:
        assert codes == set(sidecar["errorCodes"]), fixture.name
    else:
        allowed = set(sidecar["anyOfCodes"])
        assert codes, f"{fixture.name}: expected a rejection, got none"
        assert codes <= allowed, f"{fixture.name}: {codes} not within {allowed}"


def test_vendored_schema_is_byte_equal_to_the_conformance_kit() -> None:
    from importlib import resources

    vendored = (
        resources.files("agent_workflow._schema")
        .joinpath("workflow-v2alpha.schema.json")
        .read_bytes()
    )
    kit = (SPEC_DIR / "workflow-v2alpha.schema.json").read_bytes()
    assert vendored == kit, (
        "vendored schema drifted from spec/workflow-v2alpha.schema.json — re-copy it "
        "(the kit is the source of truth; the vendored copy exists for packaging)"
    )


def test_semantic_errors_are_collected_not_first_wins() -> None:
    # two independent violations in one document: unknown operation + unknown entrypoint
    doc = {
        "apiVersion": "workflow/v2alpha",
        "kind": "Workflow",
        "metadata": {"name": "multi-error"},
        "operations": {"work": {"ref": "java:test.work:v1"}},
        "nodes": [
            {"id": "do", "kind": "task", "operation": "missing-op"},
            {"id": "done", "kind": "terminate", "status": "completed"},
        ],
        "edges": [{"from": "do", "to": "done", "when": {"kind": "always"}}],
        "entrypoint": "nope",
    }
    codes = {error.code for error in validate_parsed(doc)}
    assert codes == {"UNKNOWN_OPERATION", "UNKNOWN_ENTRYPOINT"}


def test_error_paths_are_identifier_qualified() -> None:
    doc = {
        "apiVersion": "workflow/v2alpha",
        "kind": "Workflow",
        "metadata": {"name": "paths"},
        "operations": {"work": {"ref": "java:test.work:v1"}},
        "nodes": [
            {"id": "do", "kind": "task", "operation": "missing-op"},
            {"id": "done", "kind": "terminate", "status": "completed"},
        ],
        "edges": [{"from": "do", "to": "done", "when": {"kind": "always"}}],
        "entrypoint": "do",
    }
    (error,) = validate_parsed(doc)
    assert error.path == "nodes[id=do].operation"
