"""The round-trip law over the valid corpus (spec/README.md):

for every valid spec j: ``to_json(load(j))`` is byte-equal to ``canonicalize(j)``,
and reading back the written form yields an equal model.
"""

import json

import pytest
import rfc8785

from agent_workflow import load
from conftest import VALID_FIXTURES


@pytest.mark.parametrize("fixture", VALID_FIXTURES, ids=lambda p: p.stem)
def test_valid_fixture_roundtrips_to_canonical_bytes(fixture) -> None:  # type: ignore[no-untyped-def]
    raw = fixture.read_bytes()
    spec = load(raw)

    written = spec.to_json()
    canonical_input = rfc8785.dumps(json.loads(raw.decode("utf-8")))

    assert written == canonical_input, f"round-trip drift for {fixture.name}"

    reread = load(written)
    assert reread == spec, f"model re-read inequality for {fixture.name}"


def test_annotations_fixture_survives_losslessly() -> None:
    fixture = next(p for p in VALID_FIXTURES if p.stem == "annotations")
    spec = load(fixture.read_bytes())

    # DD-19: metadata- and node-level annotations round-trip losslessly
    assert spec.metadata.annotations, "metadata annotations expected in the named fixture"
    annotated_nodes = [n for n in spec.nodes if n.annotations]
    assert annotated_nodes, "node annotations expected in the named fixture"
    assert load(spec.to_json()) == spec


def test_empty_sections_fixture_preserves_present_but_empty_sections() -> None:
    fixture = next(p for p in VALID_FIXTURES if p.stem == "empty-sections")
    spec = load(fixture.read_bytes())

    # canonical rule 4: explicitly-present empty optional sections are preserved
    assert spec.types == {}
    assert spec.constants == {}
    assert spec.context_schema == {}
    assert spec.outputs == {}
    wire = json.loads(spec.to_json())
    assert wire["types"] == {}
    assert wire["outputs"] == {}
