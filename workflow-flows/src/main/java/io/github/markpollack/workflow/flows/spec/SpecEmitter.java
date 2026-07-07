package io.github.markpollack.workflow.flows.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.workflow.engine.OperationHandler;
import io.github.markpollack.workflow.engine.OperationResult;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepOperationHandler;
import io.github.markpollack.workflow.flows.workflow.EdgeCondition;
import io.github.markpollack.workflow.flows.workflow.WorkflowEdge;
import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;
import io.github.markpollack.workflow.flows.workflow.WorkflowNode;
import io.github.markpollack.workflow.spec.AlwaysCondition;
import io.github.markpollack.workflow.spec.Binding;
import io.github.markpollack.workflow.spec.DecisionResultCondition;
import io.github.markpollack.workflow.spec.DecisionSpecNode;
import io.github.markpollack.workflow.spec.OperationDeclaration;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.TerminateSpecNode;
import io.github.markpollack.workflow.spec.TerminateStatus;
import io.github.markpollack.workflow.spec.ValidationError;
import io.github.markpollack.workflow.spec.WorkflowEdgeSpec;
import io.github.markpollack.workflow.spec.WorkflowMetadata;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import io.github.markpollack.workflow.spec.WorkflowSpecNode;
import io.github.markpollack.workflow.spec.WorkflowSpecValidator;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Emits a v1 {@link WorkflowGraph} as a v2-alpha {@link WorkflowSpec}, auto-registering
 * every swallowed behavior under a structure-derived deterministic ref (DD-21). The
 * emitter exports <em>topology</em>; it never translates a lambda's meaning — a spec
 * emitted with derived refs is topologically portable while its operations remain
 * opaque outside the authoring JVM.
 *
 * <h2>The portability ladder (DD-21 — documented DX, not policy)</h2>
 * <ol>
 *   <li><b>Inline lambda</b> — swallowed as-is; ref derived from workflow + step name
 *       ({@code java:<workflow>.<step>:v1}), positional fallback ({@code step-<i>},
 *       {@code branch-<i>}, {@code decision-<i>}) only for truly anonymous logic.
 *       Topology fully portable; this operation's meaning is opaque outside this JVM.</li>
 *   <li><b>Named step</b> ({@code Step.named}, method reference) — readable derived
 *       ref, free.</li>
 *   <li><b>Explicit ref</b> ({@link SpecEmitterOptions.NodeCustomization#operation}) —
 *       catalog-visible, fully portable, reusable from Python/TS/visual authors.</li>
 * </ol>
 * Refs are a pure function of workflow structure: identical DSL code emits identical
 * refs across {@code .toSpec()} calls and JVM restarts. Two <em>distinct</em> behaviors
 * deriving the same ref is a hard {@link SpecEmissionException} (RISKS R1) — rename a
 * step; never rely on registration order.
 *
 * <h2>Auto-derivation rules (deterministic, all overridable)</h2>
 * <ul>
 *   <li><b>Node ids</b>: sanitized step name ({@code [A-Za-z0-9_-]}, others become
 *       {@code -}); a reused step instance gets {@code -2}, {@code -3} occurrence
 *       suffixes; synthetic lambda class names fall back to {@code step-<i>}.</li>
 *   <li><b>Input threading</b>: v1 chains outputs implicitly, so auto-emitted task and
 *       decision nodes bind a single-field envelope {@code {"value": {"from": ...}}} —
 *       {@code $input} at the entrypoint, {@code $node.<pred>.output} downstream —
 *       and their auto-registered handlers unwrap {@code value} before invoking the
 *       step, preserving exact v1 input semantics. Explicit
 *       {@link SpecEmitterOptions.NodeCustomization#input} bindings replace the
 *       envelope and are passed to the handler as the assembled map, un-unwrapped.</li>
 *   <li><b>Branch / decision</b>: a predicate branch becomes a {@code decision} node
 *       ({@code branch-<i>}) whose synthesized routing operation returns
 *       {@code {"outcome": "true"|"false"}}; an LLM decision becomes a {@code decision}
 *       node ({@code decision-<i>}) wrapping the v1 routing step's chosen label into
 *       the §6 outcome shape. Option nodes bind the decision's own input source (v1
 *       passes the pre-decision value into the chosen arm).</li>
 *   <li><b>Convergence</b>: when two or more option arms rejoin (or all end the
 *       workflow), each arm writes its output to the context key
 *       {@code <decision-id>.result} and the successor (or synthesized terminate)
 *       reads {@code $context.<decision-id>.result} — the IR rendering of the v1
 *       XOR-join's pass-through value.</li>
 *   <li><b>Terminates</b>: the v1 graph has no terminate vocabulary, so leaf nodes get
 *       a synthesized {@code done} terminate ({@code completed}); a single leaf binds
 *       {@code result} to its output and auto-derives
 *       {@code outputs.result} from it.</li>
 * </ul>
 *
 * <h2>Not expressible in v2-alpha</h2>
 * {@code parallel}/{@code gather} (incl. dynamic fan-out), {@code repeatUntil}/
 * {@code repeatUntilOutput}, {@code gate}, {@code supervisor}, {@code backTo}, and
 * {@code onError} all fail with a clear {@link SpecEmissionException} rather than
 * silently mis-emitting (DD-20; RISKS R2). {@code onError} is deliberately deferred:
 * IR error edges match error <em>codes</em> while v1 matches Java exception classes —
 * mapping one onto the other would silently widen or narrow recovery semantics.
 */
public final class SpecEmitter {

    /** The single-field input envelope used by auto-threaded nodes. */
    static final String VALUE_FIELD = "value";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowGraph<?, ?> graph;
    private final SpecEmitterOptions options;

    // graph node name → emitted node id (join nodes absent — they are elided)
    private final Map<String, String> idByNodeName = new LinkedHashMap<>();
    // graph node name → default-derived id (customization address)
    private final Map<String, String> defaultIdByNodeName = new LinkedHashMap<>();
    private final Set<String> usedIds = new LinkedHashSet<>();
    // step-name base → behavior identity (detects distinct behaviors sharing a name)
    private final Map<String, Object> behaviorByBase = new LinkedHashMap<>();
    private final Map<Object, Integer> occurrenceByBehavior = new IdentityHashMap<>();

    private final Map<String, OperationDeclaration> operations = new LinkedHashMap<>();
    private final Map<String, OperationHandler> handlersByRef = new LinkedHashMap<>();
    // behavior identity → operation alias (a reused step declares one operation)
    private final Map<Object, String> aliasByBehavior = new IdentityHashMap<>();

    // transformed edges (joins elided), in deterministic emission order
    private record Edge(String fromId, String toId, io.github.markpollack.workflow.spec.EdgeConditionSpec when) {
    }

    private final List<Edge> edges = new ArrayList<>();
    // decision id → declared outcomes (option labels in graph order)
    private final Map<String, List<String>> outcomesByDecision = new LinkedHashMap<>();
    // option node id → owning decision id
    private final Map<String, String> decisionByOption = new LinkedHashMap<>();
    // decisions whose arms converge through the context-write scheme
    private final Set<String> convergingDecisions = new LinkedHashSet<>();

    private SpecEmitter(WorkflowGraph<?, ?> graph, SpecEmitterOptions options) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.options = Objects.requireNonNull(options, "options");
    }

    public static WorkflowSpecEmission emit(WorkflowGraph<?, ?> graph, SpecEmitterOptions options) {
        return new SpecEmitter(graph, options).emitSpec();
    }

    private WorkflowSpecEmission emitSpec() {
        rejectNonAlpha();
        assignIds();
        transformEdges();
        Map<String, Map<String, Binding>> inputByNode = threadInputs();
        List<WorkflowSpecNode> nodes = buildNodes(inputByNode);
        Map<String, Binding> outputs = deriveOutputs(nodes);
        WorkflowSpec spec = new WorkflowSpec(
                WorkflowSpec.API_VERSION,
                WorkflowSpec.KIND,
                new WorkflowMetadata(graph.name(), options.version(), null, null),
                null,
                buildConstants(),
                null,
                operations,
                nodes,
                edges.stream().map(e -> new WorkflowEdgeSpec(e.fromId(), e.toId(), e.when(), null)).toList(),
                null,
                idByNodeName.get(graph.startNode()),
                outputs);
        selfValidate(spec);
        return new WorkflowSpecEmission(spec, handlersByRef);
    }

    // ---------------------------------------------------------------------
    // Phase 0: reject what alpha cannot say
    // ---------------------------------------------------------------------

    private void rejectNonAlpha() {
        for (WorkflowNode node : graph.nodes()) {
            switch (node) {
                case WorkflowNode.ForkNode f -> throw notExpressible(
                        "parallel/gather fan-out (fork node '" + f.name() + "')");
                case WorkflowNode.GateNode g -> throw notExpressible(
                        "gate (gate node '" + g.name() + "')");
                case WorkflowNode.LoopEntryNode l -> throw notExpressible(
                        "repeatUntil loop / supervisor (loop node '" + l.name() + "')");
                case WorkflowNode.LoopCheckNode l -> throw notExpressible(
                        "repeatUntilOutput loop (loop node '" + l.name() + "')");
                case WorkflowNode.LoopExitNode l -> throw notExpressible(
                        "loop (loop node '" + l.name() + "')");
                case WorkflowNode.StepNode s when s.step().name() != null
                        && s.step().name().startsWith("dynamic-parallel-") ->
                    throw notExpressible("dynamic parallel fan-out (step '" + s.step().name() + "')");
                default -> {
                }
            }
        }
        for (WorkflowEdge edge : graph.edges()) {
            if (edge.condition() instanceof EdgeCondition.ErrorMatch em) {
                throw new SpecEmissionException("onError(" + em.exType().getSimpleName() + ", ...) is not "
                        + "emitted in v2-alpha: IR error edges match error codes, v1 matches Java exception "
                        + "classes — mapping one onto the other would silently change recovery semantics. "
                        + "Remove the onError clause to emit, or run this workflow through the v1 path.");
            }
            if (edge.condition() instanceof EdgeCondition.BackEdge) {
                throw notExpressible("backTo cyclic back-edge (alpha graphs are DAGs, GRAPH_CYCLE)");
            }
        }
    }

    private static SpecEmissionException notExpressible(String what) {
        return new SpecEmissionException("not expressible in v2-alpha: " + what
                + ". Alpha-emittable primitives are sequential (step/then), branch, and decision "
                + "(V2-Alpha-Plus #6 adds loop/fan-out/gate). Emission fails rather than mis-emitting.");
    }

    // ---------------------------------------------------------------------
    // Phase 1: deterministic ids
    // ---------------------------------------------------------------------

    private void assignIds() {
        int stepIndex = 0;
        int branchIndex = 0;
        int decisionIndex = 0;
        for (WorkflowNode node : graph.nodes()) {
            String defaultId;
            switch (node) {
                case WorkflowNode.StepNode s -> {
                    defaultId = stepDefaultId(s.step(), stepIndex);
                    stepIndex++;
                }
                case WorkflowNode.GatewayNode g -> defaultId = "branch-" + branchIndex++;
                case WorkflowNode.DecisionNode d -> defaultId = "decision-" + decisionIndex++;
                case WorkflowNode.JoinNode j -> {
                    continue; // elided: alpha edges route arm → join-successor directly
                }
                default -> throw new IllegalStateException("unreachable: " + node);
            }
            defaultIdByNodeName.put(node.name(), defaultId);
            SpecEmitterOptions.NodeCustomization custom = options.node(defaultId);
            String id = custom != null && custom.id() != null ? custom.id() : defaultId;
            if (!usedIds.add(id)) {
                throw new SpecEmissionException("emitted node id collision: '" + id
                        + "'. Node ids must be unique; rename a step or override with .node(...).id(...)");
            }
            idByNodeName.put(node.name(), id);
        }
    }

    /**
     * Sanitized step name, {@code -N} occurrence suffix for a reused instance,
     * {@code step-<i>} positional fallback for synthetic lambda class names (DD-21:
     * JVM-synthetic names are unstable across restarts and never leak into refs).
     * Two distinct behaviors sharing one name cannot both hold the name-derived ref.
     */
    private String stepDefaultId(Step<?, ?> step, int stepIndex) {
        String name = step.name();
        String base = isSyntheticName(name) ? "step-" + stepIndex : sanitizeId(name);
        Object previous = behaviorByBase.putIfAbsent(base, step);
        if (previous != null && previous != step) {
            throw new SpecEmissionException("two distinct steps derive the ref base '" + base
                    + "' (RISKS R1): refs must be a pure function of structure. "
                    + "Give one of them a different name via Step.named(...)");
        }
        int occurrence = occurrenceByBehavior.merge(step, 1, Integer::sum);
        return occurrence == 1 ? base : base + "-" + occurrence;
    }

    private static boolean isSyntheticName(String name) {
        return name == null || name.isBlank() || name.contains("$") || name.contains("/");
    }

    private static String sanitizeId(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-' ? c : '-');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Phase 2: edge transformation (join elision)
    // ---------------------------------------------------------------------

    private void transformEdges() {
        for (WorkflowEdge edge : graph.edges()) {
            WorkflowNode from = graph.nodeByName(edge.from());
            if (from instanceof WorkflowNode.JoinNode) {
                continue; // its successor is reached by retargeted arm edges
            }
            String fromId = idByNodeName.get(edge.from());
            String toId = resolveTarget(edge.to());
            io.github.markpollack.workflow.spec.EdgeConditionSpec when = switch (edge.condition()) {
                case EdgeCondition.Unconditional u -> new AlwaysCondition();
                case EdgeCondition.OptionMatch m -> new DecisionResultCondition(m.optionName());
                case EdgeCondition.BooleanGuard b -> new DecisionResultCondition(b.value() ? "true" : "false");
                default -> throw new IllegalStateException(
                        "unreachable edge condition survived rejection: " + edge.condition());
            };
            if (when instanceof DecisionResultCondition c) {
                // decisionResult edges always target arm step nodes, never joins (v1 builder shape)
                outcomesByDecision.computeIfAbsent(fromId, k -> new ArrayList<>()).add(c.value());
                decisionByOption.put(toId, fromId);
            }
            if (toId == null) {
                continue; // arm → finish-join: the arm becomes a leaf; terminate synthesis handles it
            }
            edges.add(new Edge(fromId, toId, when));
        }
    }

    /** Follows join nodes to their unconditional successor; null when the join ends the graph. */
    private String resolveTarget(String nodeName) {
        WorkflowNode target = graph.nodeByName(nodeName);
        while (target instanceof WorkflowNode.JoinNode) {
            String next = graph.unconditionalSuccessor(target.name());
            if (next == null) {
                return null;
            }
            target = graph.nodeByName(next);
        }
        return idByNodeName.get(target.name());
    }

    // ---------------------------------------------------------------------
    // Phase 3: input threading
    // ---------------------------------------------------------------------

    private Map<String, Map<String, Binding>> threadInputs() {
        Map<String, Map<String, Binding>> inputByNode = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : idByNodeName.entrySet()) {
            String id = entry.getValue();
            SpecEmitterOptions.NodeCustomization custom = options.node(defaultIdByNodeName.get(entry.getKey()));
            Map<String, Binding> input = new LinkedHashMap<>();
            if (custom != null && !custom.input().isEmpty()) {
                custom.input().forEach((field, from) -> input.put(field, new Binding(from)));
            } else {
                input.put(VALUE_FIELD, new Binding(autoSource(id)));
            }
            inputByNode.put(id, input);
        }
        return inputByNode;
    }

    /**
     * The structural source of the value v1 would pass into this node: {@code $input}
     * at the entrypoint; the predecessor's output after a task; the owning decision's
     * own source inside an arm (v1 passes the pre-decision value into the chosen arm);
     * the convergence context key after a multi-arm join.
     */
    private String autoSource(String nodeId) {
        if (nodeId.equals(idByNodeName.get(graph.startNode()))) {
            return "$input";
        }
        String owningDecision = decisionByOption.get(nodeId);
        if (owningDecision != null) {
            return autoSource(owningDecision);
        }
        List<String> predecessors = edges.stream()
                .filter(e -> nodeId.equals(e.toId()) && e.when() instanceof AlwaysCondition)
                .map(Edge::fromId)
                .distinct()
                .toList();
        if (predecessors.size() == 1) {
            return "$node." + predecessors.get(0) + ".output";
        }
        if (predecessors.isEmpty()) {
            throw new SpecEmissionException("node '" + nodeId + "' is unreachable — cannot derive its input");
        }
        // all predecessors are arms of one decision converging through its join
        String decision = decisionByOption.get(predecessors.get(0));
        boolean oneDecision = decision != null
                && predecessors.stream().allMatch(p -> decision.equals(decisionByOption.get(p)));
        if (!oneDecision) {
            throw notExpressible("convergence of multiple independent paths into node '" + nodeId + "'");
        }
        convergingDecisions.add(decision);
        return "$context." + decision + ".result";
    }

    // ---------------------------------------------------------------------
    // Phase 4: nodes, operations, terminate synthesis, outputs
    // ---------------------------------------------------------------------

    private List<WorkflowSpecNode> buildNodes(Map<String, Map<String, Binding>> inputByNode) {
        // leaves need computing before node construction: a converging leaf set adds context writes
        List<String> leaves = idByNodeName.values().stream()
                .filter(id -> edges.stream().noneMatch(e -> id.equals(e.fromId())))
                .toList();
        String leafDecision = leaves.size() > 1 && leaves.stream()
                .allMatch(id -> decisionByOption.get(id) != null
                        && decisionByOption.get(leaves.get(0)).equals(decisionByOption.get(id)))
                ? decisionByOption.get(leaves.get(0)) : null;
        if (leafDecision != null) {
            convergingDecisions.add(leafDecision);
        }

        List<WorkflowSpecNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> entry : idByNodeName.entrySet()) {
            WorkflowNode node = graph.nodeByName(entry.getKey());
            String id = entry.getValue();
            SpecEmitterOptions.NodeCustomization custom = options.node(defaultIdByNodeName.get(entry.getKey()));
            boolean explicitInput = custom != null && !custom.input().isEmpty();
            switch (node) {
                case WorkflowNode.StepNode s -> {
                    String alias = declareOperation(id, s.step(), custom,
                            explicitInput ? new StepOperationHandler(s.step()) : envelopeUnwrapping(s.step()));
                    nodes.add(new TaskSpecNode(id, null, null, null, alias,
                            inputByNode.get(id), contextWrites(id, custom), null));
                }
                case WorkflowNode.GatewayNode g -> {
                    Step<Object, Object> routing = Step.named(id,
                            (ctx, in) -> g.predicate().test(in) ? "true" : "false");
                    String alias = declareOperation(id, routing, custom, outcomeWrapping(routing, !explicitInput));
                    nodes.add(new DecisionSpecNode(id, null, null, null, alias,
                            inputByNode.get(id), declaredOutcomes(id, custom), null));
                }
                case WorkflowNode.DecisionNode d -> {
                    String alias = declareOperation(id, d.routingStep(), custom,
                            outcomeWrapping(d.routingStep(), !explicitInput));
                    nodes.add(new DecisionSpecNode(id, null, null, null, alias,
                            inputByNode.get(id), declaredOutcomes(id, custom), null));
                }
                default -> throw new IllegalStateException("unreachable: " + node);
            }
        }

        synthesizeTerminates(nodes, leaves, leafDecision);
        return nodes;
    }

    private List<String> declaredOutcomes(String decisionId, SpecEmitterOptions.NodeCustomization custom) {
        List<String> outcomes = new ArrayList<>(outcomesByDecision.getOrDefault(decisionId, List.of()));
        if (custom != null) {
            custom.outcomeTerminates().forEach(t -> outcomes.add(t.outcome()));
        }
        return outcomes;
    }

    private Map<String, Binding> contextWrites(String nodeId, SpecEmitterOptions.NodeCustomization custom) {
        Map<String, Binding> writes = new LinkedHashMap<>();
        String decision = decisionByOption.get(nodeId);
        if (decision != null && convergingDecisions.contains(decision)) {
            writes.put(decision + ".result", new Binding("$node." + nodeId + ".output"));
        }
        if (custom != null) {
            custom.contextWrites().forEach((key, from) -> writes.put(key, new Binding(from)));
        }
        return writes.isEmpty() ? null : writes;
    }

    /**
     * Declares (once per behavior) the node's operation and captures its handler.
     * Explicit alias/ref pin the portable identity; otherwise the ref derives from
     * workflow + step name: {@code java:<workflow>.<alias>:v1}.
     */
    private String declareOperation(String nodeId, Step<?, ?> behavior,
            SpecEmitterOptions.NodeCustomization custom, OperationHandler handler) {
        String alias;
        String ref;
        if (custom != null && custom.operationAlias() != null) {
            alias = custom.operationAlias();
            ref = custom.operationRef();
        } else {
            String existing = aliasByBehavior.get(behavior);
            if (existing != null) {
                return existing; // reused step instance: one declared operation
            }
            alias = defaultAliasFor(nodeId, behavior);
            ref = "java:" + sanitizeId(graph.name()) + "." + alias + ":v1";
        }
        OperationDeclaration previous = operations.putIfAbsent(alias,
                new OperationDeclaration(ref, null, null, null, null, null));
        if (previous != null && !previous.ref().equals(ref)) {
            throw new SpecEmissionException("operation alias '" + alias + "' declared twice with different "
                    + "refs ('" + previous.ref() + "' vs '" + ref + "')");
        }
        if (handlersByRef.containsKey(ref) && previous == null) {
            throw new SpecEmissionException("two operations derive the ref '" + ref + "' (RISKS R1); "
                    + "rename a step or pin explicit refs via .node(...).operation(...)");
        }
        handlersByRef.putIfAbsent(ref, handler);
        aliasByBehavior.put(behavior, alias);
        return alias;
    }

    /** The behavior's name-derived base — the node id minus any reuse-occurrence suffix. */
    private String defaultAliasFor(String nodeId, Step<?, ?> behavior) {
        for (Map.Entry<String, Object> entry : behaviorByBase.entrySet()) {
            if (entry.getValue() == behavior) {
                return entry.getKey();
            }
        }
        return nodeId; // gateway/decision routing behaviors: the node id is the base
    }

    private void synthesizeTerminates(List<WorkflowSpecNode> nodes, List<String> leaves, String leafDecision) {
        if (!leaves.isEmpty()) {
            String doneId = freshId("done");
            Binding result = leaves.size() == 1
                    ? new Binding("$node." + leaves.get(0) + ".output")
                    : leafDecision != null ? new Binding("$context." + leafDecision + ".result") : null;
            nodes.add(new TerminateSpecNode(doneId, null, null, null, TerminateStatus.COMPLETED, result));
            leaves.forEach(leaf -> edges.add(new Edge(leaf, doneId, new AlwaysCondition())));
        }
        // decision outcomes routed straight to terminates, inserted after the decision's arm edges
        for (Map.Entry<String, String> entry : idByNodeName.entrySet()) {
            SpecEmitterOptions.NodeCustomization custom = options.node(defaultIdByNodeName.get(entry.getKey()));
            if (custom == null || custom.outcomeTerminates().isEmpty()) {
                continue;
            }
            String decisionId = entry.getValue();
            int insertAt = lastEdgeFrom(decisionId) + 1;
            for (SpecEmitterOptions.OutcomeTerminate t : custom.outcomeTerminates()) {
                if (!usedIds.add(t.terminateId())) {
                    throw new SpecEmissionException("terminate id collision: '" + t.terminateId() + "'");
                }
                nodes.add(new TerminateSpecNode(t.terminateId(), null, null, null, t.status(),
                        t.resultFrom() != null ? new Binding(t.resultFrom()) : null));
                edges.add(insertAt++, new Edge(decisionId, t.terminateId(),
                        new DecisionResultCondition(t.outcome())));
            }
        }
    }

    private int lastEdgeFrom(String nodeId) {
        for (int i = edges.size() - 1; i >= 0; i--) {
            if (edges.get(i).fromId().equals(nodeId)) {
                return i;
            }
        }
        throw new SpecEmissionException("decision '" + nodeId + "' has no outgoing edges");
    }

    private String freshId(String base) {
        String id = base;
        int n = 2;
        while (!usedIds.add(id)) {
            id = base + "-" + n++;
        }
        return id;
    }

    private Map<String, Binding> deriveOutputs(List<WorkflowSpecNode> nodes) {
        if (!options.outputs().isEmpty()) {
            Map<String, Binding> outputs = new LinkedHashMap<>();
            options.outputs().forEach((name, from) -> outputs.put(name, new Binding(from)));
            return outputs;
        }
        // auto: mirror the completed terminate's result binding, when it has one
        return nodes.stream()
                .filter(n -> n instanceof TerminateSpecNode t
                        && t.status() == TerminateStatus.COMPLETED && t.result() != null)
                .findFirst()
                .map(n -> Map.of("result", ((TerminateSpecNode) n).result()))
                .orElse(null);
    }

    private ObjectNode buildConstants() {
        if (options.constants().isEmpty()) {
            return null;
        }
        ObjectNode constants = MAPPER.createObjectNode();
        options.constants().forEach((name, value) -> constants.set(name, MAPPER.valueToTree(value)));
        return constants;
    }

    private void selfValidate(WorkflowSpec spec) {
        List<ValidationError> errors = new WorkflowSpecValidator().validate(spec);
        if (!errors.isEmpty()) {
            throw new SpecEmissionException(
                    "emitted spec failed semantic validation (emitter bug or inconsistent customization): "
                            + errors);
        }
    }

    // ---------------------------------------------------------------------
    // Swallowed-behavior handlers
    // ---------------------------------------------------------------------

    /** Unwraps the auto-threaded {@code {"value": ...}} envelope so the step sees its v1 input. */
    private static OperationHandler envelopeUnwrapping(Step<?, ?> step) {
        StepOperationHandler delegate = new StepOperationHandler(step);
        return (invocation, context, input) -> delegate.execute(invocation, context, unwrap(input));
    }

    /**
     * Adapts a routing behavior (v1 returns the chosen label) to the §6 decision output
     * shape {@code {"outcome": <label>}}; failures pass through untouched so retry and
     * error routing see the step's normalized envelope.
     */
    private static OperationHandler outcomeWrapping(Step<?, ?> routingStep, boolean unwrapEnvelope) {
        StepOperationHandler delegate = new StepOperationHandler(routingStep);
        return (invocation, context, input) -> {
            OperationResult result = delegate.execute(invocation, context,
                    unwrapEnvelope ? unwrap(input) : input);
            if (result instanceof OperationResult.Success success && success.output() != null) {
                return OperationResult.success(Map.of("outcome", String.valueOf(success.output())));
            }
            return result;
        };
    }

    private static Object unwrap(Object input) {
        return input instanceof Map<?, ?> map && map.size() == 1 && map.containsKey(VALUE_FIELD)
                ? map.get(VALUE_FIELD)
                : input;
    }
}
