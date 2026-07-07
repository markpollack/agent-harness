package io.github.markpollack.workflow.spec;

/**
 * An inline policy bundle attachable at three sites: workflow-level {@code policies},
 * operation {@code defaultPolicies}, and node {@code policies} (task and decision).
 * Precedence: node &gt; operation &gt; workflow, resolved per whole policy kind —
 * a more specific {@code retry} replaces a less specific one entirely; there is no
 * field-level merging.
 */
public record PolicyBundle(
        RetryPolicySpec retry,
        TimeoutPolicySpec timeout) {
}
