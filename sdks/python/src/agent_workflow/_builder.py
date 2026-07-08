"""The authoring surface: ``WorkflowBuilder`` + the §12 binding helpers.

Keyword-argument, declarative, and validating: ``build()`` runs BOTH validation
phases so authoring errors surface immediately (DD-13 — SDK validation is DX; the
engine re-validates regardless). Invalid states the keyword API cannot make
unrepresentable (duplicate ids, unmatched outcomes, unknown operations) are caught
at ``build()`` with the catalog's stable codes.

Emitter obligations (canonical rules 4/5): node and edge declaration order is the
emitted array order; optional sections are materialized only on first use — a
never-touched section is absent, never ``{}``.

Worker future (V2-Alpha-Plus #7): ``operation=`` is keyword-only and accepts a
declared alias (``str``) in alpha; ``@operation``-decorated callables arrive
additively in the worker increment without changing this surface.
"""

from __future__ import annotations

import json
from typing import Any, cast

from ._errors import ValidationError, WorkflowValidationError
from ._model import (
    Always,
    Binding,
    DecisionNode,
    DecisionResult,
    Edge,
    EdgeCondition,
    ErrorCondition,
    ErrorMatch,
    Execution,
    ExecutionMode,
    Metadata,
    Node,
    OperationDeclaration,
    PolicyBundle,
    RetryPolicy,
    TaskNode,
    TerminateNode,
    TerminateStatus,
    Timeout,
    WorkflowSpec,
)

# ---------------------------------------------------------------------------
# Binding helpers (emit only the frozen §12 grammar)
# ---------------------------------------------------------------------------


def from_input(key: str | None = None) -> Binding:
    """``$input`` (whole input) or ``$input.<key>`` (one top-level field)."""
    return Binding(from_="$input" if key is None else f"$input.{key}")


def from_context(key: str) -> Binding:
    """``$context.<flat-key>`` — the key is literal (dots do not navigate)."""
    return Binding(from_=f"$context.{key}")


def from_const(name: str) -> Binding:
    """``$const.<name>`` — references a declared workflow constant."""
    return Binding(from_=f"$const.{name}")


def from_node_output(node_id: str) -> Binding:
    """``$node.<id>.output`` — dotted node ids are rejected (frozen grammar)."""
    return Binding(from_=f"$node.{node_id}.output")


def from_node_decision(node_id: str) -> Binding:
    """``$node.<id>.decision`` — the stored outcome of a decision node."""
    return Binding(from_=f"$node.{node_id}.decision")


# ---------------------------------------------------------------------------
# Builder
# ---------------------------------------------------------------------------


class WorkflowBuilder:
    """Authors a v2-alpha :class:`WorkflowSpec` declaratively.

    Call order is declaration order: nodes and edges are emitted in the order the
    corresponding methods were called (canonical rule 5).
    """

    def __init__(
        self,
        name: str,
        *,
        version: str | None = None,
        labels: dict[str, str] | None = None,
        annotations: dict[str, str] | None = None,
    ) -> None:
        self._metadata = Metadata(
            name=name, version=version, labels=labels, annotations=annotations
        )
        self._constants: dict[str, Any] | None = None
        self._operations: dict[str, OperationDeclaration] = {}
        self._nodes: list[Node] = []
        self._edges: list[Edge] = []
        self._policies: PolicyBundle | None = None

    # -- declarations -------------------------------------------------------

    def constant(self, name: str, value: Any) -> WorkflowBuilder:
        """Declares a workflow constant (referenced via :func:`from_const`)."""
        if self._constants is None:
            self._constants = {}
        if name in self._constants:
            raise ValueError(f"constant '{name}' is already declared")
        self._constants[name] = value
        return self

    def operation(
        self,
        alias: str,
        *,
        ref: str,
        execution_mode: str | None = None,
        default_retry: RetryPolicy | None = None,
        default_timeout: Timeout | None = None,
    ) -> WorkflowBuilder:
        """Declares an operation under ``alias``; nodes reference the alias."""
        if alias in self._operations:
            raise ValueError(f"operation alias '{alias}' is already declared")
        default_policies = (
            PolicyBundle(retry=default_retry, timeout=default_timeout)
            if default_retry is not None or default_timeout is not None
            else None
        )
        execution = (
            Execution(mode=cast(ExecutionMode, execution_mode))
            if execution_mode is not None
            else None
        )
        self._operations[alias] = OperationDeclaration(
            ref=ref, execution=execution, default_policies=default_policies
        )
        return self

    # -- nodes ---------------------------------------------------------------

    def task(
        self,
        node_id: str,
        *,
        operation: str,
        input: dict[str, Binding] | None = None,
        context_writes: dict[str, Binding] | None = None,
        retry: RetryPolicy | None = None,
        timeout: Timeout | None = None,
        name: str | None = None,
        description: str | None = None,
        annotations: dict[str, str] | None = None,
    ) -> WorkflowBuilder:
        self._nodes.append(
            TaskNode(
                id=node_id,
                operation=operation,
                name=name,
                description=description,
                annotations=annotations,
                input=input,
                context_writes=context_writes,
                policies=_bundle(retry, timeout),
            )
        )
        return self

    def decision(
        self,
        node_id: str,
        *,
        operation: str,
        outcomes: list[str] | tuple[str, ...],
        input: dict[str, Binding] | None = None,
        retry: RetryPolicy | None = None,
        timeout: Timeout | None = None,
        name: str | None = None,
        description: str | None = None,
        annotations: dict[str, str] | None = None,
    ) -> WorkflowBuilder:
        self._nodes.append(
            DecisionNode(
                id=node_id,
                operation=operation,
                outcomes=tuple(outcomes),
                name=name,
                description=description,
                annotations=annotations,
                input=input,
                policies=_bundle(retry, timeout),
            )
        )
        return self

    def terminate(
        self,
        node_id: str,
        *,
        status: TerminateStatus,
        result: Binding | None = None,
        name: str | None = None,
        description: str | None = None,
        annotations: dict[str, str] | None = None,
    ) -> WorkflowBuilder:
        self._nodes.append(
            TerminateNode(
                id=node_id,
                status=status,
                result=result,
                name=name,
                description=description,
                annotations=annotations,
            )
        )
        return self

    # -- edges ----------------------------------------------------------------

    def edge(
        self,
        from_: str,
        to: str,
        *,
        outcome: str | None = None,
        error: ErrorMatch | None = None,
        label: str | None = None,
    ) -> WorkflowBuilder:
        """An edge: plain = always; ``outcome=`` = decisionResult; ``error=`` = error."""
        if outcome is not None and error is not None:
            raise ValueError("an edge condition is exactly one of outcome= or error=")
        when: EdgeCondition = Always()
        if outcome is not None:
            when = DecisionResult(value=outcome)
        elif error is not None:
            when = ErrorCondition(match=error)
        self._edges.append(Edge(from_=from_, to=to, when=when, label=label))
        return self

    # -- workflow-level policies ---------------------------------------------

    def policies(
        self, *, retry: RetryPolicy | None = None, timeout: Timeout | None = None
    ) -> WorkflowBuilder:
        """Workflow-level default policies (precedence: node > operation > workflow)."""
        self._policies = PolicyBundle(retry=retry, timeout=timeout)
        return self

    # -- build ----------------------------------------------------------------

    def build(
        self,
        *,
        entrypoint: str,
        outputs: dict[str, Binding] | None = None,
    ) -> WorkflowSpec:
        """Assembles the spec and runs BOTH validation phases; raises on violations."""
        spec = WorkflowSpec(
            metadata=self._metadata,
            constants=self._constants,
            operations=dict(self._operations),
            nodes=tuple(self._nodes),
            edges=tuple(self._edges),
            policies=self._policies,
            entrypoint=entrypoint,
            outputs=outputs,
        )
        from ._validation import schema_validate, validate

        errors: list[ValidationError] = validate(spec)
        errors.extend(schema_validate(json.loads(spec.to_json())))
        if errors:
            raise WorkflowValidationError(errors)
        return spec


def _bundle(retry: RetryPolicy | None, timeout: Timeout | None) -> PolicyBundle | None:
    if retry is None and timeout is None:
        return None
    return PolicyBundle(retry=retry, timeout=timeout)
