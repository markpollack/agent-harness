package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.workflow.spec.Binding;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the five frozen binding sources (§12): {@code $input}[.key],
 * {@code $context.<flat-key>}, {@code $const.<flat-key>}, {@code $node.<id>.output},
 * {@code $node.<id>.decision}. One evaluator per run: it holds the run's fixed input
 * and constants and accumulates node results as the interpreter records them; the
 * (immutable) context store is passed per call because the interpreter threads it.
 *
 * <p>Evaluation is side-effect free and deterministic (§12/§13). The grammar itself is
 * guaranteed by {@link Binding}'s constructor; this class only navigates. Per the
 * no-nulls rule, a null value anywhere (null input field, null node output) is a
 * <em>missing</em> source path — a deterministic {@link BindingResolution.Failed},
 * never a resolved null.
 *
 * <p>{@code $input.<key>} is one level of field access: {@code Map} and object
 * {@code JsonNode} inputs are read natively; any other non-null input is converted
 * once through the engine mapper (a POJO input behaves like its JSON form — what a
 * Python worker would see). {@code $const} values convert from {@code JsonNode} to
 * plain Java values on resolution.
 */
public final class BindingEvaluator {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Object workflowInput;
    private final JsonNode constants;
    private final Map<String, Object> nodeOutputs = new HashMap<>();
    private final Map<String, String> nodeDecisions = new HashMap<>();

    /**
     * @param workflowInput the run's input; may be null (a workflow may take none) —
     *                      a JSON {@code null}/missing node normalizes to absent
     * @param constants     the spec's {@code constants} section; may be null
     */
    public BindingEvaluator(Object workflowInput, JsonNode constants) {
        this.workflowInput = workflowInput instanceof JsonNode node
                && (node.isNull() || node.isMissingNode()) ? null : workflowInput;
        this.constants = constants;
    }

    /** Records a completed node's output (§12 {@code $node.<id>.output}); output may be null. */
    public void recordOutput(String nodeId, Object output) {
        nodeOutputs.put(Objects.requireNonNull(nodeId, "nodeId"), output);
    }

    /** Records a decision node's declared outcome (§15). */
    public void recordDecision(String nodeId, String outcome) {
        nodeDecisions.put(Objects.requireNonNull(nodeId, "nodeId"),
                Objects.requireNonNull(outcome, "outcome"));
    }

    public BindingResolution resolve(Binding binding, WorkflowContextStore context) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(context, "context");
        String source = binding.from();
        if (source.equals("$input")) {
            return workflowInput == null
                    ? BindingResolution.failed("missing source path: $input (workflow has no input)")
                    : BindingResolution.resolved(workflowInput);
        }
        if (source.startsWith("$input.")) {
            return resolveInputField(source, source.substring("$input.".length()));
        }
        if (source.startsWith("$context.")) {
            String key = source.substring("$context.".length());
            return context.get(key)
                    .<BindingResolution>map(BindingResolution::resolved)
                    .orElseGet(() -> BindingResolution.failed("missing source path: " + source));
        }
        if (source.startsWith("$const.")) {
            return resolveConstant(source, source.substring("$const.".length()));
        }
        // $node.<id>.output | $node.<id>.decision — node ids contain no dots (§4)
        String rest = source.substring("$node.".length());
        int lastDot = rest.lastIndexOf('.');
        String nodeId = rest.substring(0, lastDot);
        String accessor = rest.substring(lastDot + 1);
        return accessor.equals("output")
                ? resolveNodeOutput(source, nodeId)
                : resolveNodeDecision(source, nodeId);
    }

    private BindingResolution resolveInputField(String source, String key) {
        if (workflowInput == null) {
            return BindingResolution.failed("missing source path: " + source + " (workflow has no input)");
        }
        Object fields = workflowInput;
        if (fields instanceof JsonNode node) {
            if (!node.isObject()) {
                return BindingResolution.failed("input is not an object: " + source);
            }
            JsonNode field = node.get(key);
            return field == null || field.isNull()
                    ? BindingResolution.failed("missing source path: " + source)
                    : BindingResolution.resolved(toJava(field));
        }
        if (!(fields instanceof Map<?, ?>)) {
            try {
                fields = WorkflowEventJson.mapper().convertValue(workflowInput, MAP_TYPE);
            } catch (IllegalArgumentException ex) {
                return BindingResolution.failed("input is not an object: " + source);
            }
        }
        Object value = ((Map<?, ?>) fields).get(key);
        return value == null
                ? BindingResolution.failed("missing source path: " + source)
                : BindingResolution.resolved(value);
    }

    private BindingResolution resolveConstant(String source, String key) {
        if (constants == null || !constants.isObject()) {
            return BindingResolution.failed("missing source path: " + source + " (workflow declares no constants)");
        }
        JsonNode value = constants.get(key);
        return value == null || value.isNull()
                ? BindingResolution.failed("missing source path: " + source)
                : BindingResolution.resolved(toJava(value));
    }

    private BindingResolution resolveNodeOutput(String source, String nodeId) {
        if (!nodeOutputs.containsKey(nodeId)) {
            return BindingResolution.failed("missing source path: " + source + " (node has not completed)");
        }
        Object output = nodeOutputs.get(nodeId);
        return output == null
                ? BindingResolution.failed("missing source path: " + source + " (node output is empty)")
                : BindingResolution.resolved(output);
    }

    private BindingResolution resolveNodeDecision(String source, String nodeId) {
        String outcome = nodeDecisions.get(nodeId);
        return outcome == null
                ? BindingResolution.failed("missing source path: " + source + " (no decision recorded)")
                : BindingResolution.resolved(outcome);
    }

    private static Object toJava(JsonNode node) {
        return WorkflowEventJson.mapper().convertValue(node, Object.class);
    }
}
