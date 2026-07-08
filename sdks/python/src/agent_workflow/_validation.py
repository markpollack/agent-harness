"""Two-phase validation (DD-13). Phase implementations land at Step P1.3.

Phase one: JSON Schema (``spec/workflow-v2alpha.schema.json``) → ``SCHEMA_INVALID``.
Phase two: the ``spec/rules/semantic-rules.md`` catalog (SEM-01…SEM-14) — the
Conformance Kit is the source of truth; the Java validator is a sibling, not the
reference.
"""

from __future__ import annotations

from typing import Any

from ._errors import ValidationError


def validate_parsed(parsed: Any) -> list[ValidationError]:
    """Both phases over a parsed JSON document; empty list = valid."""
    # P1.3 wires the schema and semantic phases here.
    return []
