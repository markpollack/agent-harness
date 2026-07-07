package io.github.markpollack.workflow.engine;

/**
 * Usage/cost metrics for one operation attempt — the ledger the agent-controller's
 * evidence loop computes {@code J} (cost to jury-verified result) from (DESIGN.md
 * § Relationship to the Agent-Controller Stack). Carried on terminal attempt results
 * and projected onto {@code OperationSucceeded}/{@code OperationFailed} payloads;
 * first-class schema fields, not attributes, because a contract consumer depends on
 * them (DD-19 bright line).
 *
 * <p>Both fields optional: operations report what they know. Alpha keeps the shape
 * minimal ({@code tokens} total, {@code costUsd}); finer-grained breakdowns arrive as
 * additive fields.
 */
public record OperationUsage(Long tokens, Double costUsd) {

    public OperationUsage {
        if (tokens != null && tokens < 0) {
            throw new IllegalArgumentException("tokens must be >= 0: " + tokens);
        }
        if (costUsd != null && costUsd < 0) {
            throw new IllegalArgumentException("costUsd must be >= 0: " + costUsd);
        }
        if (tokens == null && costUsd == null) {
            throw new IllegalArgumentException("usage requires at least one of tokens/costUsd");
        }
    }

    public static OperationUsage of(Long tokens, Double costUsd) {
        return new OperationUsage(tokens, costUsd);
    }
}
