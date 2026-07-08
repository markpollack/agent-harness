"""Scaffold smoke test: the package imports and declares its version."""

import agent_workflow


def test_package_imports_and_has_version() -> None:
    assert agent_workflow.__version__
