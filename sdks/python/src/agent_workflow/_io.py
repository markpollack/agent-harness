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


def parse_strict(source: str | bytes) -> Any:
    """Parses JSON with duplicate-key rejection; raises WorkflowValidationError."""
    text = source.decode("utf-8") if isinstance(source, bytes) else source
    try:
        return json.loads(text, object_pairs_hook=_reject_duplicate_keys)
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
