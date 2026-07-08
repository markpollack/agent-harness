"""Python authoring SDK for the agent-workflow Workflow IR (``workflow/v2alpha``).

Authors, validates, and emits ``WorkflowSpec`` JSON executed by the JVM engine.
The SDK is an emitter, never an engine: nothing here executes workflows.

The public API is exactly what this module re-exports; ``_``-prefixed modules are
implementation detail.
"""

__version__ = "0.1.0a1"

__all__: list[str] = ["__version__"]
