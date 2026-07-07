package io.github.markpollack.workflow.spec;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One positive and one negative case per catalog rule (spec/rules/semantic-rules.md).
 * Rule ids in test names; the validator and the catalog must agree row-for-row.
 */
class WorkflowSpecValidatorTest {

    private final WorkflowSpecValidator validator = new WorkflowSpecValidator();

    // --- shared builders -------------------------------------------------------------

    private static final Map<String, OperationDeclaration> OPS = Map.of(
            "op", new OperationDeclaration("java:test.op:v1", null, null, null, null, null),
            "route-op", new OperationDeclaration("java:test.route:v1", null, null, null, null, null));

    private static TaskSpecNode task(String id) {
        return new TaskSpecNode(id, null, null, null, "op", null, null, null);
    }

    private static DecisionSpecNode decision(String id, String... outcomes) {
        return new DecisionSpecNode(id, null, null, null, "route-op", null, List.of(outcomes), null);
    }

    private static TerminateSpecNode terminate(String id) {
        return new TerminateSpecNode(id, null, null, null, TerminateStatus.COMPLETED, null);
    }

    private static WorkflowEdgeSpec always(String from, String to) {
        return new WorkflowEdgeSpec(from, to, new AlwaysCondition(), null);
    }

    private static WorkflowEdgeSpec onOutcome(String from, String to, String value) {
        return new WorkflowEdgeSpec(from, to, new DecisionResultCondition(value), null);
    }

    private static WorkflowSpec spec(List<WorkflowSpecNode> nodes, List<WorkflowEdgeSpec> edges,
            String entrypoint) {
        return new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("test", null, null, null),
                null, null, null, OPS, nodes, edges, null, entrypoint, null);
    }

    private List<String> codesOf(WorkflowSpec invalid) {
        return validator.validate(invalid).stream().map(ValidationError::code).toList();
    }

    // --- positives -------------------------------------------------------------------

    @Test
    void goldenExamplePassesBothPhases() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/spec/fixtures/valid/golden-pr-review.json")) {
            WorkflowSpec golden = new DefaultWorkflowSpecReader().read(in);
            assertThat(validator.validate(golden)).isEmpty();
        }
    }

    @Test
    void fullyFeaturedValidSpecProducesNoErrors() {
        // decision totality, node-ref bindings, policies at all three sites
        var analyzed = new TaskSpecNode("analyze", null, null, null, "op",
                Map.of("in", new Binding("$input.data")),
                Map.of("ctx.key", new Binding("$node.analyze.output")),
                new PolicyBundle(new RetryPolicySpec(3,
                        new BackoffSpec(BackoffSpec.Strategy.EXPONENTIAL, 100, 1000L, 2.0),
                        List.of(new ErrorMatcher("RATE_LIMIT"))),
                        new TimeoutPolicySpec(60_000)));
        var route = decision("route", "yes", "no");
        var onYes = new TaskSpecNode("on_yes", null, null, null, "op",
                Map.of("in", new Binding("$node.analyze.output")), null, null);
        WorkflowSpec valid = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("valid", null, null, null),
                null, null, null,
                Map.of("op", new OperationDeclaration("java:test.op:v1", null, null, null, null,
                                new PolicyBundle(new RetryPolicySpec(2,
                                        new BackoffSpec(BackoffSpec.Strategy.FIXED, 50, null, null), null), null)),
                        "route-op", OPS.get("route-op")),
                List.of(analyzed, route, onYes, terminate("done")),
                List.of(always("analyze", "route"),
                        onOutcome("route", "on_yes", "yes"),
                        onOutcome("route", "done", "no"),
                        always("on_yes", "done")),
                new PolicyBundle(new RetryPolicySpec(1, null, null), null),
                "analyze",
                Map.of("result", new Binding("$node.on_yes.output")));

        assertThat(validator.validate(valid)).isEmpty();
    }

    // --- negatives, one per rule -----------------------------------------------------

    @Test
    void sem01_duplicateNodeId() {
        var invalid = spec(List.of(task("a"), task("a"), terminate("end")),
                List.of(always("a", "end")), "a");
        assertThat(codesOf(invalid)).contains(WorkflowSpecValidator.DUPLICATE_NODE_ID);
    }

    @Test
    void sem02_edgeUnknownNode() {
        var invalid = spec(List.of(task("a"), terminate("end")),
                List.of(always("a", "ghost"), always("phantom", "end")), "a");
        var errors = validator.validate(invalid);
        assertThat(errors.stream().filter(e -> e.code().equals(WorkflowSpecValidator.EDGE_UNKNOWN_NODE)))
                .hasSize(2);
        assertThat(errors).anySatisfy(e -> assertThat(e.path()).isEqualTo("edges[from=a,to=ghost].to"));
    }

    @Test
    void sem03_unknownOperation() {
        var invalid = spec(List.of(
                        new TaskSpecNode("a", null, null, null, "nope", null, null, null),
                        terminate("end")),
                List.of(always("a", "end")), "a");
        assertThat(codesOf(invalid)).contains(WorkflowSpecValidator.UNKNOWN_OPERATION);
    }

    @Test
    void sem04_undeclaredOutcome_andDecisionEdgeFromNonDecision() {
        var invalid = spec(List.of(decision("d", "a"), task("t"), terminate("end")),
                List.of(onOutcome("d", "t", "a"),
                        onOutcome("d", "end", "undeclared"),
                        onOutcome("t", "end", "whatever")),
                "d");
        assertThat(codesOf(invalid).stream()
                .filter(WorkflowSpecValidator.UNDECLARED_OUTCOME::equals)).hasSize(2);
    }

    @Test
    void sem05_terminateWithOutgoingEdge() {
        var invalid = spec(List.of(task("a"), terminate("end")),
                List.of(always("a", "end"), always("end", "a")), "a");
        assertThat(codesOf(invalid)).contains(WorkflowSpecValidator.TERMINATE_WITH_OUTGOING_EDGE);
    }

    @Test
    void sem06_unknownEntrypoint() {
        var invalid = spec(List.of(task("a"), terminate("end")),
                List.of(always("a", "end")), "ghost");
        assertThat(codesOf(invalid)).contains(WorkflowSpecValidator.UNKNOWN_ENTRYPOINT);
    }

    @Test
    void sem08_unreachableNode() {
        var invalid = spec(List.of(task("a"), task("island"), terminate("end")),
                List.of(always("a", "end")), "a");
        var errors = validator.validate(invalid);
        assertThat(errors).anySatisfy(e -> {
            assertThat(e.code()).isEqualTo(WorkflowSpecValidator.UNREACHABLE_NODE);
            assertThat(e.path()).isEqualTo("nodes[id=island]");
        });
    }

    @Test
    void sem09_unmatchedOutcome() {
        var invalid = spec(List.of(decision("d", "a", "b"), task("t"), terminate("end")),
                List.of(onOutcome("d", "t", "a"), always("t", "end")), "d");
        var errors = validator.validate(invalid);
        assertThat(errors).anySatisfy(e -> {
            assertThat(e.code()).isEqualTo(WorkflowSpecValidator.UNMATCHED_OUTCOME);
            assertThat(e.message()).contains("'b'");
        });
    }

    @Test
    void sem10_duplicateOutcomeEdge() {
        var invalid = spec(List.of(decision("d", "a"), task("t"), task("u"), terminate("end")),
                List.of(onOutcome("d", "t", "a"), onOutcome("d", "u", "a"),
                        always("t", "end"), always("u", "end")),
                "d");
        assertThat(codesOf(invalid)).contains(WorkflowSpecValidator.DUPLICATE_OUTCOME_EDGE);
    }

    @Test
    void sem11_graphCycle() {
        var invalid = spec(List.of(task("a"), task("b"), terminate("end")),
                List.of(always("a", "b"), always("b", "a")), "a");
        var errors = validator.validate(invalid);
        assertThat(errors).anySatisfy(e -> {
            assertThat(e.code()).isEqualTo(WorkflowSpecValidator.GRAPH_CYCLE);
            assertThat(e.message()).contains("->");
        });
    }

    @Test
    void sem12_bindingUnknownNode_acrossAllBindingSites() {
        var withBadBindings = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("bad-bindings", null, null, null),
                null, null, null, OPS,
                List.of(new TaskSpecNode("a", null, null, null, "op",
                                Map.of("x", new Binding("$node.ghost1.output")),
                                Map.of("k", new Binding("$node.ghost2.output")), null),
                        new TerminateSpecNode("end", null, null, null, TerminateStatus.COMPLETED,
                                new Binding("$node.ghost3.output"))),
                List.of(always("a", "end")),
                null, "a",
                Map.of("out", new Binding("$node.ghost4.output")));

        assertThat(codesOf(withBadBindings).stream()
                .filter(WorkflowSpecValidator.BINDING_UNKNOWN_NODE::equals)).hasSize(4);
    }

    @Test
    void sem13_invalidBackoff_bothConditions_atNodeAndOperationSites() {
        var nodeSite = new TaskSpecNode("a", null, null, null, "op", null, null,
                new PolicyBundle(new RetryPolicySpec(2,
                        new BackoffSpec(BackoffSpec.Strategy.EXPONENTIAL, 100, null, null), null), null));
        var invalid = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("bad-backoff", null, null, null),
                null, null, null,
                Map.of("op", new OperationDeclaration("java:test.op:v1", null, null, null, null,
                        new PolicyBundle(new RetryPolicySpec(2,
                                new BackoffSpec(BackoffSpec.Strategy.FIXED, 5000, 100L, null), null), null)),
                        "route-op", OPS.get("route-op")),
                List.of(nodeSite, terminate("end")),
                List.of(always("a", "end")),
                null, "a", null);

        var errors = validator.validate(invalid);
        assertThat(errors.stream().filter(e -> e.code().equals(WorkflowSpecValidator.INVALID_BACKOFF)))
                .hasSize(2);
        assertThat(errors).anySatisfy(e ->
                assertThat(e.path()).isEqualTo("operations[op].defaultPolicies.retry.backoff"));
        assertThat(errors).anySatisfy(e ->
                assertThat(e.path()).isEqualTo("nodes[id=a].policies.retry.backoff"));
    }

    @Test
    void validatorCollectsAllErrorsNotJustTheFirst() {
        var invalid = spec(List.of(
                        new TaskSpecNode("a", null, null, null, "nope", null, null, null),
                        task("a"),
                        terminate("end")),
                List.of(always("a", "ghost")), "missing");
        assertThat(validator.validate(invalid).stream().map(ValidationError::code).distinct())
                .hasSizeGreaterThanOrEqualTo(3);
    }
}
