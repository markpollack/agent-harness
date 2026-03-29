package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.AgentStep;
import io.github.markpollack.workflow.flows.Step;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal routing step that calls the LLM and returns the chosen option label.
 * <p>
 * In the exploded graph, the executor follows the matching {@code OptionMatch} edge
 * to the chosen option's {@code StepNode}. This step does NOT execute the option —
 * it only determines which path to take.
 */
class DecisionStep implements Step<Object, Object>, AgentStep {

    static final String DEFAULT_PROMPT_TEMPLATE =
            "You are a workflow router. Choose the best next action based on the input.\n\n" +
            "Input: {input}\n\n" +
            "Available options:\n{options}\n\n" +
            "Respond with exactly one option name from the list above. No explanation, just the option name.";

    private final String stepName;
    private final ChatClient routingClient;
    private final Set<String> optionNames;
    private final String promptTemplate;

    DecisionStep(String stepName, ChatClient routingClient,
                 Set<String> optionNames, String promptTemplate) {
        this.stepName = Objects.requireNonNull(stepName);
        this.routingClient = Objects.requireNonNull(routingClient);
        this.optionNames = Set.copyOf(Objects.requireNonNull(optionNames));
        this.promptTemplate = Objects.requireNonNull(promptTemplate);
        if (optionNames.isEmpty()) {
            throw new IllegalArgumentException("DecisionStep requires at least one option");
        }
    }

    @Override
    public String name() {
        return stepName;
    }

    @Override
    public Object execute(AgentContext ctx, Object input) {
        String optionList = optionNames.stream()
                .map(name -> "- " + name)
                .collect(Collectors.joining("\n"));

        String prompt = promptTemplate
                .replace("{input}", input != null ? input.toString() : "")
                .replace("{options}", optionList);

        String chosen = routingClient.prompt().user(prompt).call().content();
        if (chosen != null) {
            chosen = chosen.strip();
        }

        if (!optionNames.contains(chosen)) {
            throw new IllegalStateException(
                    "Decision step '" + stepName + "': LLM returned unknown option '" + chosen
                            + "'. Valid options: " + optionNames);
        }

        return chosen;
    }
}
