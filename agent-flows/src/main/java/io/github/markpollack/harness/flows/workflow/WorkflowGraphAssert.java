package io.github.markpollack.harness.flows.workflow;

import io.github.markpollack.harness.flows.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-time type checking for compiled {@link WorkflowGraph}s.
 * <p>
 * Walks sequential {@link WorkflowNode.StepNode} pairs and verifies that each step's
 * declared {@code outputType()} is assignable to the next step's {@code inputType()}.
 * Steps returning {@code Object.class} for either method are skipped (opt-out).
 * <p>
 * This closes the DD-16 loop: {@code Workflow<I,O>} types the boundary,
 * {@code Step<?,?>} is used internally, and this utility catches mismatches
 * at test time for steps that declare their types.
 *
 * <pre>{@code
 * WorkflowGraph<String, String> graph = Workflow.<String, String>define("pipeline")
 *         .step(new ClassifyStep())
 *         .then(new TranslateStep(chat, "French"))
 *         .compile();
 * WorkflowGraphAssert.assertTypeCompatible(graph);  // throws if mismatch
 * }</pre>
 */
public final class WorkflowGraphAssert {

    private WorkflowGraphAssert() {}

    /**
     * Verifies type compatibility between sequential step pairs in the graph.
     * <p>
     * For each pair of adjacent {@link WorkflowNode.StepNode}s connected by an
     * unconditional edge, checks that the first step's {@code outputType()} is
     * assignable from the second step's {@code inputType()}. Steps that return
     * {@code Object.class} for either type method are skipped (opt-out).
     *
     * @param graph the compiled workflow graph to check
     * @throws TypeIncompatibleException if a mismatch is found
     */
    public static void assertTypeCompatible(WorkflowGraph<?, ?> graph) {
        List<String> errors = new ArrayList<>();

        for (WorkflowEdge edge : graph.edges()) {
            if (!(edge.condition() instanceof EdgeCondition.Unconditional)) {
                continue; // only check sequential pairs
            }

            var fromNode = graph.findNode(edge.from()).orElse(null);
            var toNode = graph.findNode(edge.to()).orElse(null);

            if (!(fromNode instanceof WorkflowNode.StepNode from)
                    || !(toNode instanceof WorkflowNode.StepNode to)) {
                continue; // only check StepNode → StepNode
            }

            Class<?> outputType = from.step().outputType();
            Class<?> inputType = to.step().inputType();

            // Skip if either side opts out (returns Object.class)
            if (outputType == Object.class || inputType == Object.class) {
                continue;
            }

            if (!inputType.isAssignableFrom(outputType)) {
                errors.add("Type mismatch: step '" + from.name()
                        + "' outputs " + outputType.getSimpleName()
                        + " but step '" + to.name()
                        + "' expects " + inputType.getSimpleName());
            }
        }

        if (!errors.isEmpty()) {
            throw new TypeIncompatibleException(
                    "Workflow graph has " + errors.size() + " type incompatibility(ies):\n  "
                            + String.join("\n  ", errors));
        }
    }

    /**
     * Thrown when {@link #assertTypeCompatible} finds a type mismatch.
     */
    public static class TypeIncompatibleException extends RuntimeException {
        public TypeIncompatibleException(String message) {
            super(message);
        }
    }
}
