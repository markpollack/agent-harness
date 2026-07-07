package io.github.markpollack.workflow.flows.spec;

import io.github.markpollack.workflow.spec.TerminateStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Opt-in customization for {@code .toSpec()} emission. Zero-config emission
 * ({@link #defaults()}) derives everything from workflow structure (DD-21); these
 * options are the explicit rungs of the portability ladder — stable operation refs,
 * named input bindings, declared context writes, constants — for authors who want the
 * emitted spec to be a cross-language contract rather than a Java-private artifact.
 *
 * <p>Node customizations are addressed by the node's <em>default-derived id</em>:
 * the sanitized step name for step nodes, {@code decision-<i>} / {@code branch-<i>}
 * (0-indexed, graph order) for LLM decisions and predicate branches. An {@code id}
 * override renames the emitted node; other customizations still key off the default id.
 */
public final class SpecEmitterOptions {

    private final String version;
    private final Map<String, Object> constants;
    private final Map<String, String> outputs;
    private final Map<String, NodeCustomization> nodes;

    private SpecEmitterOptions(Builder builder) {
        this.version = builder.version;
        this.constants = Map.copyOf(builder.constants);
        this.outputs = new LinkedHashMap<>(builder.outputs);
        this.nodes = new LinkedHashMap<>(builder.nodes);
    }

    public static SpecEmitterOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    String version() {
        return version;
    }

    Map<String, Object> constants() {
        return constants;
    }

    /** Explicit workflow outputs (name → binding source); empty means auto-derive. */
    Map<String, String> outputs() {
        return outputs;
    }

    NodeCustomization node(String defaultId) {
        return nodes.get(defaultId);
    }

    public static final class Builder {

        private String version;
        private final Map<String, Object> constants = new LinkedHashMap<>();
        private final Map<String, String> outputs = new LinkedHashMap<>();
        private final Map<String, NodeCustomization> nodes = new LinkedHashMap<>();

        private Builder() {
        }

        /** Sets {@code metadata.version}; absent by default (development-form specRef, §18). */
        public Builder version(String version) {
            this.version = Objects.requireNonNull(version, "version");
            return this;
        }

        /** Declares a workflow constant, referenceable as {@code $const.<name>}. */
        public Builder constant(String name, Object value) {
            constants.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Declares an explicit workflow output binding, replacing the auto-derived {@code outputs}. */
        public Builder output(String name, String from) {
            outputs.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(from, "from"));
            return this;
        }

        /** Customizes one node, addressed by its default-derived id (see class Javadoc). */
        public Builder node(String defaultId, Consumer<NodeCustomization> customizer) {
            Objects.requireNonNull(defaultId, "defaultId");
            NodeCustomization customization = nodes.computeIfAbsent(defaultId, k -> new NodeCustomization());
            customizer.accept(customization);
            return this;
        }

        public SpecEmitterOptions build() {
            return new SpecEmitterOptions(this);
        }
    }

    /** Per-node emission overrides. All are optional; anything unset is auto-derived. */
    public static final class NodeCustomization {

        private String id;
        private String operationAlias;
        private String operationRef;
        private final Map<String, String> input = new LinkedHashMap<>();
        private final Map<String, String> contextWrites = new LinkedHashMap<>();
        private final List<OutcomeTerminate> outcomeTerminates = new java.util.ArrayList<>();

        private NodeCustomization() {
        }

        /** Overrides the emitted node id ({@code [A-Za-z0-9_-]+}). */
        public NodeCustomization id(String id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        /**
         * Pins the operation alias and stable ref for this node's behavior — the
         * explicit rung of the portability ladder. The auto-registered handler is
         * registered under this ref.
         */
        public NodeCustomization operation(String alias, String ref) {
            this.operationAlias = Objects.requireNonNull(alias, "alias");
            this.operationRef = Objects.requireNonNull(ref, "ref");
            return this;
        }

        /**
         * Declares a named input binding, replacing the auto-threaded single-field
         * {@code value} envelope. With explicit bindings the operation handler receives
         * the assembled multi-field map as-is (no envelope unwrapping).
         */
        public NodeCustomization input(String field, String from) {
            input.put(Objects.requireNonNull(field, "field"), Objects.requireNonNull(from, "from"));
            return this;
        }

        /** Declares a context write ({@code contextWrites.<key>} ← binding source). */
        public NodeCustomization contextWrite(String key, String from) {
            contextWrites.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(from, "from"));
            return this;
        }

        /**
         * Decision nodes only: declares an additional outcome that routes straight to a
         * synthesized terminate node — the v2 idiom for rejection paths the v1 DSL has
         * no vocabulary for. The outcome is appended after the DSL-declared options.
         *
         * @param outcome     the outcome value the routing operation may return
         * @param status      the terminal status of the synthesized terminate node
         * @param terminateId the id of the synthesized terminate node
         * @param resultFrom  optional result binding source (null for none)
         */
        public NodeCustomization outcomeTerminate(String outcome, TerminateStatus status,
                String terminateId, String resultFrom) {
            outcomeTerminates.add(new OutcomeTerminate(
                    Objects.requireNonNull(outcome, "outcome"),
                    Objects.requireNonNull(status, "status"),
                    Objects.requireNonNull(terminateId, "terminateId"),
                    resultFrom));
            return this;
        }

        String id() {
            return id;
        }

        String operationAlias() {
            return operationAlias;
        }

        String operationRef() {
            return operationRef;
        }

        Map<String, String> input() {
            return input;
        }

        Map<String, String> contextWrites() {
            return contextWrites;
        }

        List<OutcomeTerminate> outcomeTerminates() {
            return outcomeTerminates;
        }
    }

    record OutcomeTerminate(String outcome, TerminateStatus status, String terminateId, String resultFrom) {
    }
}
