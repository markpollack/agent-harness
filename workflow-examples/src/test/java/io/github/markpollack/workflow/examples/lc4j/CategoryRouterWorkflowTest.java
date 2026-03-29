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
package io.github.markpollack.workflow.examples.lc4j;

import io.github.markpollack.workflow.flows.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transliteration of LangChain4j's {@code ConditionalAgent} routing pattern.
 *
 * <h2>LC4j equivalent (collapsed)</h2>
 * <pre>
 * ConditionalAgent router = ConditionalAgent.builder()
 *     .condition(input -> input.category().equals("medical"))
 *     .ifTrue(medicalExpert)
 *     .ifFalse(legalExpert)
 *     .build();
 * router.chat(question);
 * </pre>
 *
 * <h2>Our DSL equivalent</h2>
 * <pre>
 * Workflow.define("category-router")
 *     .branch(input -> "medical".equals(input))
 *         .then(medicalExpert)
 *         .otherwise(legalExpert)
 *     .run(question);
 * </pre>
 *
 * The LC4j {@code ConditionalAgent} requires constructing a dedicated agent type.
 * Our {@code branch()} is inline — no extra class needed. Both branches are reachable
 * from the same workflow definition, demonstrated by running the same pipeline
 * with different inputs.
 */
class CategoryRouterWorkflowTest {

    private static final Step<String, String> MEDICAL_EXPERT =
            Step.named("medical-expert", (ctx, in) -> "Medical answer for: " + in);

    private static final Step<String, String> LEGAL_EXPERT =
            Step.named("legal-expert", (ctx, in) -> "Legal answer for: " + in);

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void medicalInputShouldRouteToMedicalExpert() {
        // LC4j: ConditionalAgent routes via condition(input -> isMedical(input))
        // Our DSL: branch(output -> "medical".equals(output)).then(medical).otherwise(legal)
        String result = Workflow.<String, String>define("category-router")
                .step(Step.named("classify", (ctx, in) -> in))  // pass category through
                .branch(output -> "medical".equals(output))
                    .then(MEDICAL_EXPERT)
                    .otherwise(LEGAL_EXPERT)
                .run("medical");

        assertThat(result).isEqualTo("Medical answer for: medical");
    }

    @Test
    void legalInputShouldRouteToLegalExpert() {
        String result = Workflow.<String, String>define("category-router")
                .step(Step.named("classify", (ctx, in) -> in))
                .branch(output -> "medical".equals(output))
                    .then(MEDICAL_EXPERT)
                    .otherwise(LEGAL_EXPERT)
                .run("legal");

        assertThat(result).isEqualTo("Legal answer for: legal");
    }

    @Test
    void bothBranchesShouldBeReachableFromSameDefinition() {
        // Defines the pipeline once; executes twice with different inputs.
        // Validates that both branches compile into the graph and are independently reachable.
        Workflow<String, String> router = Workflow.<String, String>define("category-router")
                .step(Step.named("classify", (ctx, in) -> in))
                .branch(output -> "medical".equals(output))
                    .then(MEDICAL_EXPERT)
                    .otherwise(LEGAL_EXPERT)
                .build();

        AgentContext ctx = AgentContext.create();
        assertThat(router.execute(ctx, "medical")).startsWith("Medical");
        assertThat(router.execute(ctx, "legal")).startsWith("Legal");
    }
}
