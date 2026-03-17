/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.harness.flows.workflow;

import io.github.markpollack.harness.flows.AgentContext;
import io.github.markpollack.harness.flows.Step;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Internal step that routes workflow execution based on LLM choice.
 * <p>
 * The LLM receives the current input and a list of named options,
 * then responds with exactly one option name. The chosen option's step
 * is executed and its output returned.
 * <p>
 * This is an internal type created by {@link Workflow.DecisionBuilder} — users
 * interact with {@code .decision(client).option("name", step).end()} in the DSL,
 * not with this class directly.
 */
class DecisionStep implements Step<Object, Object> {

    static final String DEFAULT_PROMPT_TEMPLATE =
            "You are a workflow router. Choose the best next action based on the input.\n\n" +
            "Input: {input}\n\n" +
            "Available options:\n{options}\n\n" +
            "Respond with exactly one option name from the list above. No explanation, just the option name.";

    private final String stepName;
    private final ChatClient routingClient;
    private final Map<String, Step<?, ?>> options;
    private final String promptTemplate;

    DecisionStep(String stepName, ChatClient routingClient,
                 Map<String, Step<?, ?>> options, String promptTemplate) {
        this.stepName = Objects.requireNonNull(stepName, "stepName must not be null");
        this.routingClient = Objects.requireNonNull(routingClient, "routingClient must not be null");
        this.options = new LinkedHashMap<>(Objects.requireNonNull(options, "options must not be null"));
        this.promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate must not be null");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("DecisionStep requires at least one option");
        }
    }

    @Override
    public String name() {
        return stepName;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object execute(AgentContext ctx, Object input) {
        String optionList = options.keySet().stream()
                .map(name -> "- " + name)
                .collect(Collectors.joining("\n"));

        String prompt = promptTemplate
                .replace("{input}", input != null ? input.toString() : "")
                .replace("{options}", optionList);

        String chosen = routingClient.prompt().user(prompt).call().content();
        if (chosen != null) {
            chosen = chosen.strip();
        }

        Step chosenStep = options.get(chosen);
        if (chosenStep == null) {
            throw new IllegalStateException(
                    "Decision step '" + stepName + "': LLM returned unknown option '" + chosen
                            + "'. Valid options: " + options.keySet());
        }

        return chosenStep.execute(ctx, input);
    }
}
