package io.github.markpollack.workflow.flows.steps;

import io.github.markpollack.agents.claude.ClaudeAgentModel;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.workflow.StepTransition;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;
import io.github.markpollack.workflow.flows.workflow.Workflow;
import io.github.markpollack.workflow.flows.workflow.WorkflowExecutor;
import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises the full trace path wiring:
 * ClaudeAgentModel (with traceDir) → AgentClientStep → WorkflowExecutor → StepTransition.
 * <p>
 * Requires the {@code claude} CLI on PATH with a valid authentication session.
 * Enable via: {@code AGENT_CLIENT_IT=true ./mvnw test -pl workflow-flows -Dtest=AgentClientStepTraceIT}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AGENT_CLIENT_IT", matches = "true")
class AgentClientStepTraceIT {

    @Test
    void tracePathFlowsThroughWorkflowToTransition(@TempDir Path traceDir) throws IOException {
        try (ClaudeAgentModel model = ClaudeAgentModel.builder()
                .traceDir(traceDir)
                .build()) {

            var coreClient = io.github.markpollack.agents.client.AgentClient.create(model);

            // Bridge agent-client to workflow-flows AgentClient with trace support
            AgentClient workflowClient = new AgentClient() {
                @Override
                public String execute(String prompt, AgentContext ctx) {
                    return executeForResult(prompt, ctx).text();
                }

                @Override
                public ExecutionResult executeForResult(String prompt, AgentContext ctx) {
                    AgentClientResponse response = coreClient.run(prompt);
                    String tracePath = (String) response.getMetadata().get("tracePath");
                    return new ExecutionResult(response.getResult(), tracePath);
                }
            };

            AgentClientStep agentStep = AgentClientStep.of(workflowClient,
                    "Reply with only the word 'pong'. Nothing else.");

            TraceRecorder recorder = TraceRecorder.inMemory();
            WorkflowExecutor executor = new WorkflowExecutor(recorder);
            AgentContext ctx = AgentContext.withRunId("trace-it");

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("trace-it")
                    .step(agentStep)
                    .compile();

            String result = executor.execute(graph, ctx, "");

            // 1. Agent responded
            assertThat(result).isNotBlank();

            // 2. Trace file was written to traceDir
            List<Path> traceFiles;
            try (var stream = Files.list(traceDir)) {
                traceFiles = stream
                        .filter(p -> p.toString().endsWith(".jsonl"))
                        .toList();
            }
            assertThat(traceFiles).as("trace JSONL file written").hasSize(1);
            assertThat(Files.size(traceFiles.get(0))).isGreaterThan(0);

            // 3. Trace path wired through to StepTransition
            List<StepTransition> transitions = recorder.getTrace("trace-it");
            assertThat(transitions).isNotEmpty();

            StepTransition agentTransition = transitions.stream()
                    .filter(t -> t.tracePath() != null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No transition carries tracePath"));

            assertThat(agentTransition.tracePath())
                    .isEqualTo(traceFiles.get(0).toAbsolutePath().toString());
        }
    }
}
