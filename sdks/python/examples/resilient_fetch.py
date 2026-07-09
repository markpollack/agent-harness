"""A resilient fetch pipeline: retry policies, per-attempt timeouts, error-edge routing.

Shows the policy surface: ``max_attempts`` counts the FIRST attempt (3 = three
total), backoff is deterministic (no jitter — the engine never randomizes delays),
and an ``error=`` edge routes a retry-exhausted failure to a fallback path instead
of failing the run.

    uv run python examples/resilient_fetch.py > resilient-fetch.workflow.json
"""

import sys

from agent_workflow import (
    Backoff,
    ErrorMatch,
    ErrorMatcher,
    RetryPolicy,
    Timeout,
    WorkflowBuilder,
    from_input,
    from_node_output,
)


def build_resilient_fetch():  # type: ignore[no-untyped-def]
    wf = WorkflowBuilder("resilient-fetch", version="1.0.0")

    wf.operation("fetch", ref="java:http.fetch:v1")
    wf.operation("cache-read", ref="java:cache.read:v1")

    wf.task(
        "call_api",
        operation="fetch",
        input={"url": from_input("url")},
        retry=RetryPolicy(
            max_attempts=3,  # three attempts total, not three retries
            backoff=Backoff.exponential(initial_millis=500, multiplier=2.0, max_millis=10_000),
            retry_on=(ErrorMatcher(code="RATE_LIMIT"),),
        ),
        timeout=Timeout(per_attempt_millis=30_000),
    )
    wf.task("read_cache", operation="cache-read", input={"url": from_input("url")})

    wf.terminate("done", status="completed", result=from_node_output("call_api"))
    wf.terminate("served_stale", status="completed", result=from_node_output("read_cache"))

    wf.edge("call_api", "done")
    # after retry exhaustion, a matching failure routes here instead of failing the run
    wf.edge("call_api", "read_cache", error=ErrorMatch(code="RATE_LIMIT"))
    wf.edge("read_cache", "served_stale")

    return wf.build(entrypoint="call_api")


if __name__ == "__main__":
    sys.stdout.buffer.write(build_resilient_fetch().to_json())
