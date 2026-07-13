"""Two-phase validation (DD-13).

Phase one: JSON Schema against the vendored ``workflow-v2alpha.schema.json``
(byte-equal to the Conformance Kit's — a test pins the copy) → ``SCHEMA_INVALID``.
Phase two: the ``spec/rules/semantic-rules.md`` catalog, SEM-01…SEM-14 — the Kit is
the source of truth; the Java validator is a sibling implementation, not the
reference. Violations are COLLECTED (never first-wins) with identifier-qualified
paths (``nodes[id=route].outcomes``).

Catalog-pinned suppressions: SEM-08 (reachability) runs only when SEM-06
(entrypoint) passes; SEM-08 and SEM-11 (cycles) operate on the edge subset whose
endpoints exist (SEM-02 reports the rest). SEM-07/SEM-14's raw-JSON surface is
covered at parse time (strict duplicate-key rejection in ``_io``); their builder
surface is enforced by the Stage-P2 builder.
"""

from __future__ import annotations

import json
from importlib import resources
from typing import Any

from jsonschema import Draft202012Validator

from ._errors import ValidationError
from ._model import (
    Backoff,
    Binding,
    DecisionNode,
    DecisionResult,
    Edge,
    Node,
    PolicyBundle,
    TaskNode,
    TerminateNode,
    WorkflowSpec,
)


def _schema_validator() -> Draft202012Validator:
    schema_text = (
        resources.files("agent_workflow._schema")
        .joinpath("workflow-v2alpha.schema.json")
        .read_text(encoding="utf-8")
    )
    return Draft202012Validator(json.loads(schema_text))


_VALIDATOR = _schema_validator()


def schema_validate(parsed: Any) -> list[ValidationError]:
    """Phase one: wire shape. Every violation reports ``SCHEMA_INVALID``."""
    return [
        ValidationError(code="SCHEMA_INVALID", path=error.json_path, message=error.message)
        for error in _VALIDATOR.iter_errors(parsed)
    ]


def validate_parsed(parsed: Any) -> list[ValidationError]:
    """Both phases over a parsed JSON document; empty list = valid.

    Phase two runs only on schema-valid documents (catalog preamble), mirroring the
    Java reader: schema → bind → semantic.
    """
    schema_errors = schema_validate(parsed)
    if schema_errors:
        return schema_errors
    return validate(WorkflowSpec.from_wire(parsed))


def validate(spec: WorkflowSpec) -> list[ValidationError]:
    """Phase two: the semantic rule catalog over a bound model."""
    errors: list[ValidationError] = []
    node_ids = {node.id for node in spec.nodes}

    _sem_01_duplicate_node_ids(spec.nodes, errors)
    _sem_02_edge_endpoints(spec.edges, node_ids, errors)
    _sem_03_unknown_operations(spec, errors)
    _sem_04_undeclared_outcomes(spec, errors)
    _sem_05_terminate_outgoing(spec, errors)
    entrypoint_ok = _sem_06_entrypoint(spec, node_ids, errors)

    valid_edges = [edge for edge in spec.edges if edge.from_ in node_ids and edge.to in node_ids]
    if entrypoint_ok:
        _sem_08_reachability(spec, valid_edges, errors)
    _sem_09_10_outcome_coverage(spec, errors)
    _sem_11_cycles(valid_edges, errors)
    _sem_12_binding_node_refs(spec, node_ids, errors)
    _sem_13_backoff(spec, errors)
    _sem_15_number_domain(spec, errors)
    return errors


# ---------------------------------------------------------------------------
# Rules
# ---------------------------------------------------------------------------


def _sem_01_duplicate_node_ids(nodes: tuple[Node, ...], errors: list[ValidationError]) -> None:
    seen: set[str] = set()
    reported: set[str] = set()
    for node in nodes:
        if node.id in seen and node.id not in reported:
            reported.add(node.id)
            errors.append(
                ValidationError(
                    code="DUPLICATE_NODE_ID",
                    path=f"nodes[id={node.id}]",
                    message=f"two nodes share the id '{node.id}'",
                )
            )
        seen.add(node.id)


def _sem_02_edge_endpoints(
    edges: tuple[Edge, ...], node_ids: set[str], errors: list[ValidationError]
) -> None:
    for edge in edges:
        for endpoint, value in (("from", edge.from_), ("to", edge.to)):
            if value not in node_ids:
                errors.append(
                    ValidationError(
                        code="EDGE_UNKNOWN_NODE",
                        path=f"edges[from={edge.from_},to={edge.to}].{endpoint}",
                        message=f"edge {endpoint} references unknown node '{value}'",
                    )
                )


def _sem_03_unknown_operations(spec: WorkflowSpec, errors: list[ValidationError]) -> None:
    for node in spec.nodes:
        if isinstance(node, TaskNode | DecisionNode) and node.operation not in spec.operations:
            errors.append(
                ValidationError(
                    code="UNKNOWN_OPERATION",
                    path=f"nodes[id={node.id}].operation",
                    message=f"operation '{node.operation}' is not declared in operations",
                )
            )


def _sem_04_undeclared_outcomes(spec: WorkflowSpec, errors: list[ValidationError]) -> None:
    by_id = {node.id: node for node in spec.nodes}
    for edge in spec.edges:
        if not isinstance(edge.when, DecisionResult):
            continue
        source = by_id.get(edge.from_)
        outcomes = source.outcomes if isinstance(source, DecisionNode) else ()
        if source is not None and edge.when.value not in outcomes:
            errors.append(
                ValidationError(
                    code="UNDECLARED_OUTCOME",
                    path=f"edges[from={edge.from_},to={edge.to}].when.value",
                    message=f"'{edge.when.value}' is not a declared outcome of '{edge.from_}'",
                )
            )


def _sem_05_terminate_outgoing(spec: WorkflowSpec, errors: list[ValidationError]) -> None:
    terminate_ids = {node.id for node in spec.nodes if isinstance(node, TerminateNode)}
    for edge in spec.edges:
        if edge.from_ in terminate_ids:
            errors.append(
                ValidationError(
                    code="TERMINATE_WITH_OUTGOING_EDGE",
                    path=f"edges[from={edge.from_},to={edge.to}]",
                    message=f"terminate node '{edge.from_}' must have no outgoing edges",
                )
            )


def _sem_06_entrypoint(
    spec: WorkflowSpec, node_ids: set[str], errors: list[ValidationError]
) -> bool:
    if spec.entrypoint not in node_ids:
        errors.append(
            ValidationError(
                code="UNKNOWN_ENTRYPOINT",
                path="entrypoint",
                message=f"entrypoint references unknown node '{spec.entrypoint}'",
            )
        )
        return False
    return True


def _sem_08_reachability(
    spec: WorkflowSpec, valid_edges: list[Edge], errors: list[ValidationError]
) -> None:
    adjacency: dict[str, list[str]] = {}
    for edge in valid_edges:
        adjacency.setdefault(edge.from_, []).append(edge.to)
    reachable: set[str] = set()
    stack = [spec.entrypoint]
    while stack:
        current = stack.pop()
        if current in reachable:
            continue
        reachable.add(current)
        stack.extend(adjacency.get(current, ()))
    for node in spec.nodes:
        if node.id not in reachable:
            errors.append(
                ValidationError(
                    code="UNREACHABLE_NODE",
                    path=f"nodes[id={node.id}]",
                    message=f"node '{node.id}' is not reachable from the entrypoint",
                )
            )


def _sem_09_10_outcome_coverage(spec: WorkflowSpec, errors: list[ValidationError]) -> None:
    for node in spec.nodes:
        if not isinstance(node, DecisionNode):
            continue
        edge_counts = {outcome: 0 for outcome in node.outcomes}
        for edge in spec.edges:
            if (
                edge.from_ == node.id
                and isinstance(edge.when, DecisionResult)
                and edge.when.value in edge_counts
            ):
                edge_counts[edge.when.value] += 1
        for outcome, count in edge_counts.items():
            if count == 0:
                errors.append(
                    ValidationError(
                        code="UNMATCHED_OUTCOME",
                        path=f"nodes[id={node.id}].outcomes",
                        message=f"declared outcome '{outcome}' has no decisionResult edge (§16)",
                    )
                )
            elif count > 1:
                errors.append(
                    ValidationError(
                        code="DUPLICATE_OUTCOME_EDGE",
                        path=f"nodes[id={node.id}].outcomes",
                        message=f"declared outcome '{outcome}' has {count} decisionResult "
                        f"edges (§16 requires exactly one match)",
                    )
                )


def _sem_11_cycles(valid_edges: list[Edge], errors: list[ValidationError]) -> None:
    adjacency: dict[str, list[str]] = {}
    for edge in valid_edges:
        adjacency.setdefault(edge.from_, []).append(edge.to)

    WHITE, GRAY, BLACK = 0, 1, 2
    color: dict[str, int] = dict.fromkeys(adjacency, WHITE)
    stack_path: list[str] = []

    def visit(node: str) -> list[str] | None:
        color[node] = GRAY
        stack_path.append(node)
        for successor in adjacency.get(node, ()):
            state = color.get(successor, WHITE)
            if state == GRAY:
                cycle_start = stack_path.index(successor)
                return [*stack_path[cycle_start:], successor]
            if state == WHITE:
                witness = visit(successor)
                if witness is not None:
                    return witness
        stack_path.pop()
        color[node] = BLACK
        return None

    for start in list(adjacency):
        if color.get(start, WHITE) == WHITE:
            witness = visit(start)
            if witness is not None:
                errors.append(
                    ValidationError(
                        code="GRAPH_CYCLE",
                        path="edges",
                        message="alpha graphs are DAGs; cycle: " + " -> ".join(witness),
                    )
                )
                return


def _iter_bindings(spec: WorkflowSpec) -> list[tuple[str, Binding]]:
    sites: list[tuple[str, Binding]] = []
    for node in spec.nodes:
        if isinstance(node, TaskNode | DecisionNode) and node.input:
            sites.extend(
                (f"nodes[id={node.id}].input.{key}", binding) for key, binding in node.input.items()
            )
        if isinstance(node, TaskNode) and node.context_writes:
            sites.extend(
                (f"nodes[id={node.id}].contextWrites.{key}", binding)
                for key, binding in node.context_writes.items()
            )
        if isinstance(node, TerminateNode) and node.result is not None:
            sites.append((f"nodes[id={node.id}].result", node.result))
    if spec.outputs:
        sites.extend((f"outputs.{key}", binding) for key, binding in spec.outputs.items())
    return sites


def _sem_12_binding_node_refs(
    spec: WorkflowSpec, node_ids: set[str], errors: list[ValidationError]
) -> None:
    for path, binding in _iter_bindings(spec):
        source = binding.from_
        if not source.startswith("$node."):
            continue
        rest = source[len("$node.") :]
        referenced = rest.split(".", 1)[0]
        if referenced not in node_ids:
            errors.append(
                ValidationError(
                    code="BINDING_UNKNOWN_NODE",
                    path=path,
                    message=f"binding references unknown node '{referenced}'",
                )
            )


def _sem_13_backoff(spec: WorkflowSpec, errors: list[ValidationError]) -> None:
    sites: list[tuple[str, PolicyBundle | None]] = [("policies", spec.policies)]
    sites.extend(
        (f"operations[{alias}].defaultPolicies", declaration.default_policies)
        for alias, declaration in spec.operations.items()
    )
    sites.extend(
        (f"nodes[id={node.id}].policies", node.policies)
        for node in spec.nodes
        if isinstance(node, TaskNode | DecisionNode)
    )
    for site, bundle in sites:
        backoff = bundle.retry.backoff if bundle and bundle.retry else None
        if backoff is None:
            continue
        for violation in _backoff_violations(backoff):
            errors.append(
                ValidationError(
                    code="INVALID_BACKOFF", path=f"{site}.retry.backoff", message=violation
                )
            )


_SAFE_INT_MAX = 2**53 - 1


def _sem_15_number_domain(spec: WorkflowSpec, errors: list[ValidationError]) -> None:
    """SEM-15: open-section numbers must be within the IEEE-754 safe integer range.

    Beyond 2^53-1 the three languages diverge on the canonical form (Python keeps an
    exact int; JS/Java round through a double); forbidding it makes all three
    fail-closed on the same input (I-JSON). Also rejects large-magnitude floats — the
    only rule enforceable identically post-parse.
    """
    sites: list[tuple[str, Any]] = [
        ("constants", spec.constants),
        ("types", spec.types),
        ("contextSchema", spec.context_schema),
    ]
    for alias, decl in spec.operations.items():
        sites.append((f"operations[{alias}].inputSchema", decl.input_schema))
        sites.append((f"operations[{alias}].outputSchema", decl.output_schema))
    for path, section in sites:
        if section is not None:
            _scan_numbers(section, path, errors)


def _scan_numbers(value: Any, path: str, errors: list[ValidationError]) -> None:
    if isinstance(value, bool):
        return
    if isinstance(value, (int, float)) and abs(value) > _SAFE_INT_MAX:
        errors.append(
            ValidationError(
                code="NUMBER_OUT_OF_RANGE",
                path=path,
                message=f"number {value} is outside the IEEE-754 safe integer range "
                "(2^53-1); represent large values as strings",
            )
        )
    elif isinstance(value, dict):
        for key, sub in value.items():
            _scan_numbers(sub, f"{path}.{key}", errors)
    elif isinstance(value, list):
        for i, sub in enumerate(value):
            _scan_numbers(sub, f"{path}[{i}]", errors)


def _backoff_violations(backoff: Backoff) -> list[str]:
    violations: list[str] = []
    if backoff.strategy == "exponential" and backoff.multiplier is None:
        violations.append("exponential backoff requires a multiplier")
    if backoff.max_millis is not None and backoff.initial_millis > backoff.max_millis:
        violations.append(
            f"initialMillis ({backoff.initial_millis}) exceeds maxMillis ({backoff.max_millis})"
        )
    return violations
