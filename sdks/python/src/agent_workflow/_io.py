"""Reading specs from JSON: strict parse → (validation, wired in at P1.3) → model bind.

Duplicate raw-JSON keys are invalid input (canonical-form note; SEM-07/SEM-14
implementation notes): parsing MUST NOT silently last-wins, so ``loads`` installs an
``object_pairs_hook`` that rejects them.
"""

from __future__ import annotations

import json
from typing import Any

from ._errors import ValidationError, WorkflowValidationError
from ._model import WorkflowSpec


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    obj: dict[str, Any] = {}
    for key, value in pairs:
        if key in obj:
            raise WorkflowValidationError(
                [
                    ValidationError(
                        code="SCHEMA_INVALID",
                        path="$",
                        message=f"duplicate object key: {key!r}",
                    )
                ]
            )
        obj[key] = value
    return obj


def _reject_non_finite(literal: str) -> Any:
    # Python's json module accepts NaN/Infinity by default; they are not valid JSON
    # (RFC 8259) and are outside the JCS number domain — reject at parse, never crash
    # later at canonical emission.
    raise WorkflowValidationError(
        [
            ValidationError(
                code="SCHEMA_INVALID",
                path="$",
                message=f"non-finite number literal is not valid JSON: {literal}",
            )
        ]
    )


def parse_strict(source: str | bytes) -> Any:
    """Parses JSON strictly: duplicate keys and non-finite literals are rejected."""
    text = source.decode("utf-8") if isinstance(source, bytes) else source
    try:
        return json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite,
        )
    except WorkflowValidationError:
        raise
    except json.JSONDecodeError as ex:
        raise WorkflowValidationError(
            [ValidationError(code="SCHEMA_INVALID", path="$", message=f"not valid JSON: {ex}")]
        ) from ex


def load(source: str | bytes) -> WorkflowSpec:
    """JSON → validated ``WorkflowSpec``.

    Contract (mirrors the Java ``WorkflowSpecReader``): both validation phases run
    before binding — a returned spec is valid by construction. Failures raise
    :class:`WorkflowValidationError` carrying the Conformance Kit's stable codes.
    """
    parsed = parse_strict(source)
    from ._validation import validate_parsed

    errors = validate_parsed(parsed)
    if errors:
        raise WorkflowValidationError(errors)
    return WorkflowSpec.from_wire(parsed)
