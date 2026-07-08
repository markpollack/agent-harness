"""Python authoring SDK for the agent-workflow Workflow IR (``workflow/v2alpha``).

Authors, validates, and emits ``WorkflowSpec`` JSON executed by the JVM engine.
The SDK is an emitter, never an engine: nothing here executes workflows.

The public API is exactly what this module re-exports; ``_``-prefixed modules are
implementation detail.
"""

from ._errors import ValidationError, WorkflowValidationError
from ._io import load
from ._model import (
    Always,
    Backoff,
    Binding,
    DecisionNode,
    DecisionResult,
    Edge,
    EdgeCondition,
    ErrorCondition,
    ErrorMatch,
    ErrorMatcher,
    Execution,
    Metadata,
    Node,
    OperationDeclaration,
    PolicyBundle,
    RetryPolicy,
    TaskNode,
    TerminateNode,
    Timeout,
    WorkflowSpec,
)

__version__ = "0.1.0a1"

__all__ = [
    "Always",
    "Backoff",
    "Binding",
    "DecisionNode",
    "DecisionResult",
    "Edge",
    "EdgeCondition",
    "ErrorCondition",
    "ErrorMatch",
    "ErrorMatcher",
    "Execution",
    "Metadata",
    "Node",
    "OperationDeclaration",
    "PolicyBundle",
    "RetryPolicy",
    "TaskNode",
    "TerminateNode",
    "Timeout",
    "ValidationError",
    "WorkflowSpec",
    "WorkflowValidationError",
    "__version__",
    "load",
]
