"""Canonical JSON Form (DD-15): RFC 8785 JCS via ``rfc8785`` + the content rules.

The content rules (no nulls, absent-vs-empty, semantic array order) are emitter
obligations enforced upstream in the model/builder — by the time a wire dict reaches
this module it contains no ``None`` values, and canonicalization never adds or drops
anything (canonical rule 4: an explicitly-present empty section is preserved).
Hand-rolling JCS number formatting is the DD-15-rejected alternative; ``rfc8785``
implements the ECMAScript serialization the contract pins (``1.0`` → ``1``).
"""

from __future__ import annotations

from typing import Any

import rfc8785


def canonical_bytes(value: Any) -> bytes:
    """RFC 8785 canonical UTF-8 bytes of a parsed JSON value."""
    return rfc8785.dumps(value)
