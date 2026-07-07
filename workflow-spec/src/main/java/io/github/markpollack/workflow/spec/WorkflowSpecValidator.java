package io.github.markpollack.workflow.spec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase two of two-phase validation (DD-5): cross-field graph rules over the bound
 * model. Every rule implemented here has a row in the normative catalog
 * {@code spec/rules/semantic-rules.md} with a stable error code pinned by the fixture
 * corpus — validator and catalog must agree row-for-row (DD-13).
 *
 * <p>All errors are collected (never first-error-wins); error paths are
 * identifier-qualified ({@code nodes[id=route].outcomes}) rather than positional.
 */
public final class WorkflowSpecValidator {

    public static final String DUPLICATE_NODE_ID = "DUPLICATE_NODE_ID";
    public static final String EDGE_UNKNOWN_NODE = "EDGE_UNKNOWN_NODE";
    public static final String UNKNOWN_OPERATION = "UNKNOWN_OPERATION";
    public static final String UNDECLARED_OUTCOME = "UNDECLARED_OUTCOME";
    public static final String TERMINATE_WITH_OUTGOING_EDGE = "TERMINATE_WITH_OUTGOING_EDGE";
    public static final String UNKNOWN_ENTRYPOINT = "UNKNOWN_ENTRYPOINT";
    public static final String UNREACHABLE_NODE = "UNREACHABLE_NODE";
    public static final String UNMATCHED_OUTCOME = "UNMATCHED_OUTCOME";
    public static final String DUPLICATE_OUTCOME_EDGE = "DUPLICATE_OUTCOME_EDGE";
    public static final String GRAPH_CYCLE = "GRAPH_CYCLE";
    public static final String BINDING_UNKNOWN_NODE = "BINDING_UNKNOWN_NODE";
    public static final String INVALID_BACKOFF = "INVALID_BACKOFF";

    /** Validates the spec, returning every violation found (empty = semantically valid). */
    public List<ValidationError> validate(WorkflowSpec spec) {
        List<ValidationError> errors = new ArrayList<>();

        // SEM-01 node id uniqueness (first occurrence wins for downstream rule evaluation)
        Map<String, WorkflowSpecNode> nodesById = new LinkedHashMap<>();
        for (WorkflowSpecNode node : spec.nodes()) {
            if (nodesById.putIfAbsent(node.id(), node) != null) {
                errors.add(new ValidationError(DUPLICATE_NODE_ID,
                        "nodes[id=" + node.id() + "]",
                        "duplicate node id '" + node.id() + "'"));
            }
        }

        // SEM-02 edge endpoints reference existing nodes
        for (WorkflowEdgeSpec edge : spec.edges()) {
            if (!nodesById.containsKey(edge.from())) {
                errors.add(new ValidationError(EDGE_UNKNOWN_NODE,
                        edgePath(edge) + ".from",
                        "edge references unknown node '" + edge.from() + "'"));
            }
            if (!nodesById.containsKey(edge.to())) {
                errors.add(new ValidationError(EDGE_UNKNOWN_NODE,
                        edgePath(edge) + ".to",
                        "edge references unknown node '" + edge.to() + "'"));
            }
        }

        // SEM-03 task/decision operations are declared
        for (WorkflowSpecNode node : spec.nodes()) {
            String operation = switch (node) {
                case TaskSpecNode task -> task.operation();
                case DecisionSpecNode decision -> decision.operation();
                case TerminateSpecNode ignored -> null;
            };
            if (operation != null && !spec.operations().containsKey(operation)) {
                errors.add(new ValidationError(UNKNOWN_OPERATION,
                        "nodes[id=" + node.id() + "].operation",
                        "node references undeclared operation '" + operation + "'"));
            }
        }

        // SEM-04 decisionResult edges reference declared outcomes of a decision source
        for (WorkflowEdgeSpec edge : spec.edges()) {
            if (edge.when() instanceof DecisionResultCondition condition) {
                WorkflowSpecNode source = nodesById.get(edge.from());
                if (source == null) {
                    continue; // already EDGE_UNKNOWN_NODE
                }
                List<String> declared = source instanceof DecisionSpecNode decision
                        ? decision.outcomes() : List.of();
                if (!declared.contains(condition.value())) {
                    errors.add(new ValidationError(UNDECLARED_OUTCOME,
                            edgePath(edge) + ".when.value",
                            source instanceof DecisionSpecNode
                                    ? "decision '" + edge.from() + "' does not declare outcome '"
                                        + condition.value() + "' (declared: " + declared + ")"
                                    : "decisionResult edge from non-decision node '" + edge.from() + "'"));
                }
            }
        }

        // SEM-05 terminate nodes have no outgoing edges
        for (WorkflowEdgeSpec edge : spec.edges()) {
            if (nodesById.get(edge.from()) instanceof TerminateSpecNode) {
                errors.add(new ValidationError(TERMINATE_WITH_OUTGOING_EDGE,
                        edgePath(edge),
                        "terminate node '" + edge.from() + "' must not have outgoing edges"));
            }
        }

        // SEM-06 entrypoint references an existing node
        boolean entrypointKnown = nodesById.containsKey(spec.entrypoint());
        if (!entrypointKnown) {
            errors.add(new ValidationError(UNKNOWN_ENTRYPOINT,
                    "entrypoint",
                    "entrypoint references unknown node '" + spec.entrypoint() + "'"));
        }

        // SEM-08 reachability from entrypoint (only meaningful with a valid entrypoint)
        if (entrypointKnown) {
            Set<String> reachable = reachableFrom(spec.entrypoint(), spec.edges(), nodesById.keySet());
            for (String nodeId : nodesById.keySet()) {
                if (!reachable.contains(nodeId)) {
                    errors.add(new ValidationError(UNREACHABLE_NODE,
                            "nodes[id=" + nodeId + "]",
                            "node '" + nodeId + "' is not reachable from entrypoint '"
                                    + spec.entrypoint() + "'"));
                }
            }
        }

        // SEM-09/SEM-10 decision-outcome totality: every declared outcome covered by
        // exactly one decisionResult edge
        for (WorkflowSpecNode node : nodesById.values()) {
            if (node instanceof DecisionSpecNode decision) {
                Map<String, Integer> coverage = new HashMap<>();
                for (WorkflowEdgeSpec edge : spec.edges()) {
                    if (edge.from().equals(decision.id())
                            && edge.when() instanceof DecisionResultCondition condition) {
                        coverage.merge(condition.value(), 1, Integer::sum);
                    }
                }
                for (String outcome : decision.outcomes()) {
                    int count = coverage.getOrDefault(outcome, 0);
                    if (count == 0) {
                        errors.add(new ValidationError(UNMATCHED_OUTCOME,
                                "nodes[id=" + decision.id() + "].outcomes",
                                "declared outcome '" + outcome + "' of decision '" + decision.id()
                                        + "' has no decisionResult edge (guaranteed runtime edge-selection failure)"));
                    } else if (count > 1) {
                        errors.add(new ValidationError(DUPLICATE_OUTCOME_EDGE,
                                "nodes[id=" + decision.id() + "].outcomes",
                                "declared outcome '" + outcome + "' of decision '" + decision.id()
                                        + "' has " + count + " decisionResult edges (guaranteed runtime multi-match)"));
                    }
                }
            }
        }

        // SEM-11 acyclicity: alpha graphs are DAGs — checkpoint identity (workflowRunId,
        // nodeId) presumes at-most-once node execution; iteration arrives with the
        // post-alpha loop node kind, never via back-edges
        findCycle(spec.edges(), nodesById.keySet()).ifPresent(cycle ->
                errors.add(new ValidationError(GRAPH_CYCLE,
                        "edges",
                        "workflow graph contains a cycle: " + String.join(" -> ", cycle))));

        // SEM-12 $node.<id> bindings reference existing nodes
        for (WorkflowSpecNode node : spec.nodes()) {
            switch (node) {
                case TaskSpecNode task -> {
                    checkBindingTargets(task.input(), "nodes[id=" + task.id() + "].input",
                            nodesById.keySet(), errors);
                    checkBindingTargets(task.contextWrites(), "nodes[id=" + task.id() + "].contextWrites",
                            nodesById.keySet(), errors);
                }
                case DecisionSpecNode decision -> checkBindingTargets(decision.input(),
                        "nodes[id=" + decision.id() + "].input", nodesById.keySet(), errors);
                case TerminateSpecNode terminate -> {
                    if (terminate.result() != null) {
                        checkBindingTarget(terminate.result(),
                                "nodes[id=" + terminate.id() + "].result", nodesById.keySet(), errors);
                    }
                }
            }
        }
        checkBindingTargets(spec.outputs(), "outputs", nodesById.keySet(), errors);

        // SEM-13 backoff cross-field rules, at all three policy attachment sites
        validatePolicies(spec.policies(), "policies", errors);
        for (Map.Entry<String, OperationDeclaration> entry : spec.operations().entrySet()) {
            validatePolicies(entry.getValue().defaultPolicies(),
                    "operations[" + entry.getKey() + "].defaultPolicies", errors);
        }
        for (WorkflowSpecNode node : spec.nodes()) {
            PolicyBundle policies = switch (node) {
                case TaskSpecNode task -> task.policies();
                case DecisionSpecNode decision -> decision.policies();
                case TerminateSpecNode ignored -> null;
            };
            validatePolicies(policies, "nodes[id=" + node.id() + "].policies", errors);
        }

        return List.copyOf(errors);
    }

    private static String edgePath(WorkflowEdgeSpec edge) {
        return "edges[from=" + edge.from() + ",to=" + edge.to() + "]";
    }

    private static Set<String> reachableFrom(String start, List<WorkflowEdgeSpec> edges, Set<String> nodeIds) {
        Set<String> reachable = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        reachable.add(start);
        frontier.push(start);
        while (!frontier.isEmpty()) {
            String current = frontier.pop();
            for (WorkflowEdgeSpec edge : edges) {
                if (edge.from().equals(current) && nodeIds.contains(edge.to()) && reachable.add(edge.to())) {
                    frontier.push(edge.to());
                }
            }
        }
        return reachable;
    }

    /** Iterative DFS cycle detection over edges with valid endpoints; returns one witness cycle. */
    private static java.util.Optional<List<String>> findCycle(List<WorkflowEdgeSpec> edges, Set<String> nodeIds) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowEdgeSpec edge : edges) {
            if (nodeIds.contains(edge.from()) && nodeIds.contains(edge.to())) {
                adjacency.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            }
        }
        Set<String> done = new HashSet<>();
        for (String root : nodeIds) {
            if (done.contains(root)) {
                continue;
            }
            Map<String, String> parent = new HashMap<>();
            Set<String> inPath = new HashSet<>();
            Deque<String[]> stack = new ArrayDeque<>();
            stack.push(new String[]{root, null});
            while (!stack.isEmpty()) {
                String[] frame = stack.peek();
                String nodeId = frame[0];
                if (frame[1] == null) {
                    frame[1] = "visited";
                    inPath.add(nodeId);
                    for (String next : adjacency.getOrDefault(nodeId, List.of())) {
                        if (inPath.contains(next)) {
                            List<String> cycle = new ArrayList<>();
                            cycle.add(next);
                            for (String walk = nodeId; walk != null && !walk.equals(next);
                                    walk = parent.get(walk)) {
                                cycle.add(walk);
                            }
                            cycle.add(next);
                            java.util.Collections.reverse(cycle);
                            return java.util.Optional.of(cycle);
                        }
                        if (!done.contains(next)) {
                            parent.put(next, nodeId);
                            stack.push(new String[]{next, null});
                        }
                    }
                } else {
                    stack.pop();
                    inPath.remove(nodeId);
                    done.add(nodeId);
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static void checkBindingTargets(Map<String, Binding> bindings, String path,
            Set<String> nodeIds, List<ValidationError> errors) {
        if (bindings == null) {
            return;
        }
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            checkBindingTarget(entry.getValue(), path + "." + entry.getKey(), nodeIds, errors);
        }
    }

    private static void checkBindingTarget(Binding binding, String path,
            Set<String> nodeIds, List<ValidationError> errors) {
        String from = binding.from();
        if (!from.startsWith("$node.")) {
            return;
        }
        String rest = from.substring("$node.".length());
        int dot = rest.indexOf('.');
        String nodeId = dot < 0 ? rest : rest.substring(0, dot);
        if (!nodeIds.contains(nodeId)) {
            errors.add(new ValidationError(BINDING_UNKNOWN_NODE, path,
                    "binding '" + from + "' references unknown node '" + nodeId + "'"));
        }
    }

    private static void validatePolicies(PolicyBundle policies, String path, List<ValidationError> errors) {
        if (policies == null || policies.retry() == null || policies.retry().backoff() == null) {
            return;
        }
        BackoffSpec backoff = policies.retry().backoff();
        if (backoff.strategy() == BackoffSpec.Strategy.EXPONENTIAL && backoff.multiplier() == null) {
            errors.add(new ValidationError(INVALID_BACKOFF, path + ".retry.backoff",
                    "exponential backoff requires 'multiplier'"));
        }
        if (backoff.maxMillis() != null && backoff.initialMillis() > backoff.maxMillis()) {
            errors.add(new ValidationError(INVALID_BACKOFF, path + ".retry.backoff",
                    "initialMillis (" + backoff.initialMillis() + ") must be <= maxMillis ("
                            + backoff.maxMillis() + ")"));
        }
    }
}
