"""Structured validation errors (DD-13: codes + identifier-qualified paths)."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ValidationError:
    """One violation: a stable error code, an identifier-qualified path, a message.

    Codes come from the Conformance Kit: ``SCHEMA_INVALID`` for wire-shape (phase one)
    and the ``spec/rules/semantic-rules.md`` catalog codes for phase two. Paths use
    identifiers, not indices (``nodes[id=route].outcomes``).
    """

    code: str
    path: str
    message: str


class WorkflowValidationError(Exception):
    """Raised when a spec fails validation; carries every collected violation."""

    def __init__(self, errors: Sequence[ValidationError]) -> None:
        self.errors: tuple[ValidationError, ...] = tuple(errors)
        summary = "; ".join(f"{e.code} at {e.path}: {e.message}" for e in self.errors)
        super().__init__(f"workflow spec validation failed ({len(self.errors)}): {summary}")
