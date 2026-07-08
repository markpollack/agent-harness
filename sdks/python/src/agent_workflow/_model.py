"""The v2-alpha WorkflowSpec model: frozen dataclasses mirroring the wire format 1:1.

The JSON wire format is the Published Language; these classes are the Python
conformist rendering (DD-9/DD-10). Construction enforces the cheap local invariants
the Java ``SpecInvariants`` mirrors from the schema — invalid states are
unrepresentable at the same places. Cross-field graph rules are the semantic
validator's job (two-phase validation, DD-13).

Wire-mapping conventions:

- Attributes are ``snake_case``; ``to_wire``/``from_wire`` translate to the camelCase
  wire keys (``from`` — a Python keyword — maps to ``from_``).
- **No nulls** (canonical rule 2): ``None`` means *absent*; an absent optional field
  is omitted from the wire. A *present-but-empty* optional section (``{}``) is a
  distinct state and round-trips as-is (canonical rule 4) — hence ``None`` vs ``{}``.
- Open sections (``types``, ``constants``, ``contextSchema``, embedded schemas) are
  carried verbatim as parsed JSON values and round-trip losslessly, like the Java
  model's deep-copied ``JsonNode``s.
- Collections are stored as plain ``dict``/``tuple``; the dataclasses are frozen and
  treat their contents as immutable by convention (the mainstream Python SDK idiom —
  deep-freezing is noise the type system cannot enforce anyway).
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, ClassVar, Literal, cast

API_VERSION = "workflow/v2alpha"
KIND = "Workflow"

_NODE_ID = re.compile(r"^[A-Za-z0-9_-]+$")
_MAX_MILLIS = 9_007_199_254_740_991  # 2^53 - 1 (schema bound on every millis field)
_MAX_ATTEMPTS = 2_147_483_647  # 2^31 - 1

TerminateStatus = Literal["completed", "failed", "cancelled", "aborted"]
BackoffStrategy = Literal["fixed", "exponential"]
ExecutionMode = Literal["request-response"]

_TERMINATE_STATUSES = ("completed", "failed", "cancelled", "aborted")
_BACKOFF_STRATEGIES = ("fixed", "exponential")
_EXECUTION_MODES = ("request-response",)


def _require_non_blank(value: str, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field_name} must be a non-blank string")
    return value


def _require_node_id(value: str) -> str:
    if not isinstance(value, str) or not _NODE_ID.fullmatch(value):
        raise ValueError(
            f"node id must match [A-Za-z0-9_-]+ (no dots; binding grammar, alpha spec §4/§12): "
            f"{value!r}"
        )
    return value


def _as_int(value: object, field_name: str) -> int:
    """Integer fields accept integral JSON forms (``3.0`` — the integer-forms pin)."""
    if isinstance(value, bool):
        raise ValueError(f"{field_name} must be an integer, got a boolean")
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    raise ValueError(f"{field_name} must be an integer, got {value!r}")


def _require_millis(value: int, field_name: str) -> int:
    if not 0 <= value <= _MAX_MILLIS:
        raise ValueError(f"{field_name} must be within [0, 2^53-1], got {value}")
    return value


# ---------------------------------------------------------------------------
# Bindings (§12 — the frozen path grammar)
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Binding:
    """A declared data binding: ``{"from": "$input.url"}`` (frozen §12 grammar)."""

    from_: str

    _GRAMMAR: ClassVar[re.Pattern[str]] = re.compile(
        r"^\$(input(\.[A-Za-z0-9_-]+)?"
        r"|context\.[\x21-\x7E]+"
        r"|const\.[\x21-\x7E]+"
        r"|node\.[A-Za-z0-9_-]+\.(output|decision))$"
    )

    def __post_init__(self) -> None:
        if not isinstance(self.from_, str) or not self._GRAMMAR.fullmatch(self.from_):
            raise ValueError(
                f"binding source does not match the v2-alpha grammar (alpha spec §12): "
                f"{self.from_!r}"
            )

    def to_wire(self) -> dict[str, Any]:
        return {"from": self.from_}

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> Binding:
        return cls(from_=obj["from"])


def _binding_map_to_wire(bindings: dict[str, Binding]) -> dict[str, Any]:
    return {key: binding.to_wire() for key, binding in bindings.items()}


def _binding_map_from_wire(obj: dict[str, Any]) -> dict[str, Binding]:
    return {key: Binding.from_wire(value) for key, value in obj.items()}


# ---------------------------------------------------------------------------
# Policies (§4 — inline {retry, timeout} bundles)
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Backoff:
    strategy: BackoffStrategy
    initial_millis: int
    max_millis: int | None = None
    multiplier: float | None = None

    def __post_init__(self) -> None:
        if self.strategy not in _BACKOFF_STRATEGIES:
            raise ValueError(f"backoff strategy must be one of {_BACKOFF_STRATEGIES}")
        _require_millis(self.initial_millis, "initialMillis")
        if self.max_millis is not None:
            _require_millis(self.max_millis, "maxMillis")
        if self.multiplier is not None and self.multiplier < 1.0:
            raise ValueError(f"multiplier must be >= 1.0, got {self.multiplier}")

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {"strategy": self.strategy, "initialMillis": self.initial_millis}
        if self.max_millis is not None:
            wire["maxMillis"] = self.max_millis
        if self.multiplier is not None:
            wire["multiplier"] = self.multiplier
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> Backoff:
        return cls(
            strategy=cast(BackoffStrategy, obj["strategy"]),
            initial_millis=_as_int(obj["initialMillis"], "initialMillis"),
            max_millis=(_as_int(obj["maxMillis"], "maxMillis") if "maxMillis" in obj else None),
            multiplier=(float(obj["multiplier"]) if "multiplier" in obj else None),
        )


@dataclass(frozen=True, slots=True)
class ErrorMatcher:
    """Exact error-code matcher used in ``retryOn`` lists."""

    code: str

    def __post_init__(self) -> None:
        _require_non_blank(self.code, "retryOn code")

    def to_wire(self) -> dict[str, Any]:
        return {"code": self.code}

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> ErrorMatcher:
        return cls(code=obj["code"])


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    """``max_attempts`` counts the FIRST attempt: 3 means three total, not three retries."""

    max_attempts: int
    backoff: Backoff | None = None
    retry_on: tuple[ErrorMatcher, ...] | None = None

    def __post_init__(self) -> None:
        if not 1 <= self.max_attempts <= _MAX_ATTEMPTS:
            raise ValueError(f"maxAttempts must be within [1, 2^31-1], got {self.max_attempts}")
        if self.retry_on is not None:
            object.__setattr__(self, "retry_on", tuple(self.retry_on))

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {"maxAttempts": self.max_attempts}
        if self.backoff is not None:
            wire["backoff"] = self.backoff.to_wire()
        if self.retry_on is not None:
            wire["retryOn"] = [matcher.to_wire() for matcher in self.retry_on]
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> RetryPolicy:
        return cls(
            max_attempts=_as_int(obj["maxAttempts"], "maxAttempts"),
            backoff=Backoff.from_wire(obj["backoff"]) if "backoff" in obj else None,
            retry_on=(
                tuple(ErrorMatcher.from_wire(m) for m in obj["retryOn"])
                if "retryOn" in obj
                else None
            ),
        )


@dataclass(frozen=True, slots=True)
class Timeout:
    per_attempt_millis: int

    def __post_init__(self) -> None:
        _require_millis(self.per_attempt_millis, "perAttemptMillis")

    def to_wire(self) -> dict[str, Any]:
        return {"perAttemptMillis": self.per_attempt_millis}

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> Timeout:
        return cls(per_attempt_millis=_as_int(obj["perAttemptMillis"], "perAttemptMillis"))


@dataclass(frozen=True, slots=True)
class PolicyBundle:
    retry: RetryPolicy | None = None
    timeout: Timeout | None = None

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {}
        if self.retry is not None:
            wire["retry"] = self.retry.to_wire()
        if self.timeout is not None:
            wire["timeout"] = self.timeout.to_wire()
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> PolicyBundle:
        return cls(
            retry=RetryPolicy.from_wire(obj["retry"]) if "retry" in obj else None,
            timeout=Timeout.from_wire(obj["timeout"]) if "timeout" in obj else None,
        )


# ---------------------------------------------------------------------------
# Operations
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Execution:
    mode: ExecutionMode

    def __post_init__(self) -> None:
        if self.mode not in _EXECUTION_MODES:
            raise ValueError(f"execution mode must be one of {_EXECUTION_MODES}")

    def to_wire(self) -> dict[str, Any]:
        return {"mode": self.mode}

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> Execution:
        return cls(mode=cast(ExecutionMode, obj["mode"]))


@dataclass(frozen=True, slots=True)
class OperationDeclaration:
    """A declared operation; ``ref`` is the stable capability identifier (DD-3/DD-12)."""

    ref: str
    input_schema_ref: str | None = None
    input_schema: Any = None
    output_schema: Any = None
    execution: Execution | None = None
    default_policies: PolicyBundle | None = None

    def __post_init__(self) -> None:
        _require_non_blank(self.ref, "operation ref")

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {"ref": self.ref}
        if self.input_schema_ref is not None:
            wire["inputSchemaRef"] = self.input_schema_ref
        if self.input_schema is not None:
            wire["inputSchema"] = self.input_schema
        if self.output_schema is not None:
            wire["outputSchema"] = self.output_schema
        if self.execution is not None:
            wire["execution"] = self.execution.to_wire()
        if self.default_policies is not None:
            wire["defaultPolicies"] = self.default_policies.to_wire()
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> OperationDeclaration:
        return cls(
            ref=obj["ref"],
            input_schema_ref=obj.get("inputSchemaRef"),
            input_schema=obj.get("inputSchema"),
            output_schema=obj.get("outputSchema"),
            execution=Execution.from_wire(obj["execution"]) if "execution" in obj else None,
            default_policies=(
                PolicyBundle.from_wire(obj["defaultPolicies"]) if "defaultPolicies" in obj else None
            ),
        )


# ---------------------------------------------------------------------------
# Nodes (the closed task | decision | terminate algebra)
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class TaskNode:
    id: str
    operation: str
    name: str | None = None
    description: str | None = None
    annotations: dict[str, str] | None = None
    input: dict[str, Binding] | None = None
    context_writes: dict[str, Binding] | None = None
    policies: PolicyBundle | None = None

    def __post_init__(self) -> None:
        _require_node_id(self.id)
        _require_non_blank(self.operation, "operation")

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {"id": self.id, "kind": "task"}
        _base_node_to_wire(self, wire)
        wire["operation"] = self.operation
        if self.input is not None:
            wire["input"] = _binding_map_to_wire(self.input)
        if self.context_writes is not None:
            wire["contextWrites"] = _binding_map_to_wire(self.context_writes)
        if self.policies is not None:
            wire["policies"] = self.policies.to_wire()
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> TaskNode:
        return cls(
            id=obj["id"],
            operation=obj["operation"],
            name=obj.get("name"),
            description=obj.get("description"),
            annotations=obj.get("annotations"),
            input=_binding_map_from_wire(obj["input"]) if "input" in obj else None,
            context_writes=(
                _binding_map_from_wire(obj["contextWrites"]) if "contextWrites" in obj else None
            ),
            policies=PolicyBundle.from_wire(obj["policies"]) if "policies" in obj else None,
        )


@dataclass(frozen=True, slots=True)
class DecisionNode:
    id: str
    operation: str
    outcomes: tuple[str, ...]
    name: str | None = None
    description: str | None = None
    annotations: dict[str, str] | None = None
    input: dict[str, Binding] | None = None
    policies: PolicyBundle | None = None

    def __post_init__(self) -> None:
        _require_node_id(self.id)
        _require_non_blank(self.operation, "operation")
        object.__setattr__(self, "outcomes", tuple(self.outcomes))
        if not self.outcomes:
            raise ValueError(f"decision node '{self.id}' must declare at least one outcome")
        if len(set(self.outcomes)) != len(self.outcomes):
            raise ValueError(f"outcomes of decision '{self.id}' must not contain duplicates")
        for outcome in self.outcomes:
            _require_non_blank(outcome, f"outcome of decision '{self.id}'")

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {"id": self.id, "kind": "decision"}
        _base_node_to_wire(self, wire)
        wire["operation"] = self.operation
        if self.input is not None:
            wire["input"] = _binding_map_to_wire(self.input)
        wire["outcomes"] = list(self.outcomes)
        if self.policies is not None:
            wire["policies"] = self.policies.to_wire()
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> DecisionNode:
        return cls(
            id=obj["id"],
            operation=obj["operation"],
            outcomes=tuple(obj["outcomes"]),
            name=obj.get("name"),
            description=obj.get("description"),
            annotations=obj.get("annotations"),
            input=_binding_map_from_wire(obj["input"]) if "input" in obj else None,
            policies=PolicyBundle.from_wire(obj["policies"]) if "policies" in obj else None,
        )


@dataclass(frozen=True, slots=True)
class TerminateNode:
    id: str
    status: TerminateStatus
    name: str | None = None
    description: str | None = None
    annotations: dict[str, str] | None = None
    result: Binding | None = None

    def __post_init__(self) -> None:
        _require_node_id(self.id)
        if self.status not in _TERMINATE_STATUSES:
            raise ValueError(f"terminate status must be one of {_TERMINATE_STATUSES}")

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {"id": self.id, "kind": "terminate"}
        _base_node_to_wire(self, wire)
        wire["status"] = self.status
        if self.result is not None:
            wire["result"] = self.result.to_wire()
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> TerminateNode:
        return cls(
            id=obj["id"],
            status=cast(TerminateStatus, obj["status"]),
            name=obj.get("name"),
            description=obj.get("description"),
            annotations=obj.get("annotations"),
            result=Binding.from_wire(obj["result"]) if "result" in obj else None,
        )


Node = TaskNode | DecisionNode | TerminateNode

_NODE_KINDS: dict[str, type[TaskNode] | type[DecisionNode] | type[TerminateNode]] = {
    "task": TaskNode,
    "decision": DecisionNode,
    "terminate": TerminateNode,
}


def _base_node_to_wire(node: Node, wire: dict[str, Any]) -> None:
    if node.name is not None:
        wire["name"] = node.name
    if node.description is not None:
        wire["description"] = node.description
    if node.annotations is not None:
        wire["annotations"] = dict(node.annotations)


def node_from_wire(obj: dict[str, Any]) -> Node:
    kind = obj.get("kind")
    node_type = _NODE_KINDS.get(cast(str, kind))
    if node_type is None:
        raise ValueError(f"unknown node kind: {kind!r}")
    return node_type.from_wire(obj)


# ---------------------------------------------------------------------------
# Edges (the closed always | decisionResult | error condition algebra)
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Always:
    def to_wire(self) -> dict[str, Any]:
        return {"kind": "always"}


@dataclass(frozen=True, slots=True)
class DecisionResult:
    value: str

    def __post_init__(self) -> None:
        _require_non_blank(self.value, "decisionResult value")

    def to_wire(self) -> dict[str, Any]:
        return {"kind": "decisionResult", "value": self.value}


@dataclass(frozen=True, slots=True)
class ErrorMatch:
    """Error-envelope matcher for ``error`` edges: at least one criterion present."""

    code: str | None = None
    retryable: bool | None = None

    def __post_init__(self) -> None:
        if self.code is None and self.retryable is None:
            raise ValueError("errorMatch requires at least one of 'code' or 'retryable'")

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {}
        if self.code is not None:
            wire["code"] = self.code
        if self.retryable is not None:
            wire["retryable"] = self.retryable
        return wire


@dataclass(frozen=True, slots=True)
class ErrorCondition:
    match: ErrorMatch

    def to_wire(self) -> dict[str, Any]:
        return {"kind": "error", "match": self.match.to_wire()}


EdgeCondition = Always | DecisionResult | ErrorCondition


def _condition_from_wire(obj: dict[str, Any]) -> EdgeCondition:
    kind = obj.get("kind")
    if kind == "always":
        return Always()
    if kind == "decisionResult":
        return DecisionResult(value=obj["value"])
    if kind == "error":
        match = obj["match"]
        return ErrorCondition(
            match=ErrorMatch(code=match.get("code"), retryable=match.get("retryable"))
        )
    raise ValueError(f"unknown edge condition kind: {kind!r}")


@dataclass(frozen=True, slots=True)
class Edge:
    from_: str
    to: str
    when: EdgeCondition = field(default_factory=Always)
    label: str | None = None

    def __post_init__(self) -> None:
        _require_non_blank(self.from_, "edge from")
        _require_non_blank(self.to, "edge to")

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {"from": self.from_, "to": self.to, "when": self.when.to_wire()}
        if self.label is not None:
            wire["label"] = self.label
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> Edge:
        return cls(
            from_=obj["from"],
            to=obj["to"],
            when=_condition_from_wire(obj["when"]),
            label=obj.get("label"),
        )


# ---------------------------------------------------------------------------
# Metadata + the spec root
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Metadata:
    name: str
    version: str | None = None
    labels: dict[str, str] | None = None
    annotations: dict[str, str] | None = None

    def __post_init__(self) -> None:
        _require_non_blank(self.name, "metadata name")

    def to_wire(self) -> dict[str, Any]:
        wire: dict[str, Any] = {"name": self.name}
        if self.version is not None:
            wire["version"] = self.version
        if self.labels is not None:
            wire["labels"] = dict(self.labels)
        if self.annotations is not None:
            wire["annotations"] = dict(self.annotations)
        return wire

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> Metadata:
        return cls(
            name=obj["name"],
            version=obj.get("version"),
            labels=obj.get("labels"),
            annotations=obj.get("annotations"),
        )


@dataclass(frozen=True, slots=True)
class WorkflowSpec:
    """The v2-alpha WorkflowSpec: language-neutral, data-only (apiVersion frozen).

    ``types``/``constants``/``context_schema`` are open sections carried verbatim;
    ``None`` = absent, ``{}`` = explicitly present and empty (both round-trip as-is,
    canonical rule 4).
    """

    metadata: Metadata
    operations: dict[str, OperationDeclaration]
    nodes: tuple[Node, ...]
    edges: tuple[Edge, ...]
    entrypoint: str
    api_version: str = API_VERSION
    kind: str = KIND
    types: dict[str, Any] | None = None
    constants: dict[str, Any] | None = None
    context_schema: dict[str, Any] | None = None
    policies: PolicyBundle | None = None
    outputs: dict[str, Binding] | None = None

    def __post_init__(self) -> None:
        if self.api_version != API_VERSION:
            raise ValueError(f"unsupported apiVersion: {self.api_version!r}")
        if self.kind != KIND:
            raise ValueError(f"unsupported kind: {self.kind!r}")
        if not self.operations:
            raise ValueError("operations must not be empty")
        object.__setattr__(self, "nodes", tuple(self.nodes))
        object.__setattr__(self, "edges", tuple(self.edges))
        if not self.nodes:
            raise ValueError("nodes must not be empty")
        _require_non_blank(self.entrypoint, "entrypoint")

    def to_wire(self) -> dict[str, Any]:
        """The plain-dict wire form (pre-canonicalization)."""
        wire: dict[str, Any] = {"apiVersion": self.api_version, "kind": self.kind}
        wire["metadata"] = self.metadata.to_wire()
        if self.types is not None:
            wire["types"] = self.types
        if self.constants is not None:
            wire["constants"] = self.constants
        if self.context_schema is not None:
            wire["contextSchema"] = self.context_schema
        wire["operations"] = {
            alias: declaration.to_wire() for alias, declaration in self.operations.items()
        }
        wire["nodes"] = [node.to_wire() for node in self.nodes]
        wire["edges"] = [edge.to_wire() for edge in self.edges]
        if self.policies is not None:
            wire["policies"] = self.policies.to_wire()
        wire["entrypoint"] = self.entrypoint
        if self.outputs is not None:
            wire["outputs"] = _binding_map_to_wire(self.outputs)
        return wire

    def to_json(self) -> bytes:
        """Canonical UTF-8 bytes (RFC 8785 + the content rules) — the equivalence form."""
        from . import _canonical

        return _canonical.canonical_bytes(self.to_wire())

    @classmethod
    def from_wire(cls, obj: dict[str, Any]) -> WorkflowSpec:
        return cls(
            api_version=obj["apiVersion"],
            kind=obj["kind"],
            metadata=Metadata.from_wire(obj["metadata"]),
            types=obj.get("types"),
            constants=obj.get("constants"),
            context_schema=obj.get("contextSchema"),
            operations={
                alias: OperationDeclaration.from_wire(declaration)
                for alias, declaration in obj["operations"].items()
            },
            nodes=tuple(node_from_wire(node) for node in obj["nodes"]),
            edges=tuple(Edge.from_wire(edge) for edge in obj["edges"]),
            policies=PolicyBundle.from_wire(obj["policies"]) if "policies" in obj else None,
            entrypoint=obj["entrypoint"],
            outputs=_binding_map_from_wire(obj["outputs"]) if "outputs" in obj else None,
        )
