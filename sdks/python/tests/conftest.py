"""Shared fixtures: the Conformance Kit at the repo root (DD-9 — read directly)."""

from pathlib import Path

import pytest

SPEC_DIR = Path(__file__).resolve().parents[3] / "spec"
VALID_FIXTURES = sorted((SPEC_DIR / "fixtures" / "valid").glob("*.json"))
INVALID_FIXTURES = sorted(
    p
    for p in (SPEC_DIR / "fixtures" / "invalid").glob("*.json")
    if not p.name.endswith(".expected.json")
)


@pytest.fixture(scope="session")
def spec_dir() -> Path:
    assert SPEC_DIR.is_dir(), f"Conformance Kit not found at {SPEC_DIR}"
    return SPEC_DIR
