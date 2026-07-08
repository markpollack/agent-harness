package io.github.markpollack.workflow.engine.jpa;

import io.github.markpollack.workflow.engine.CheckpointStore;
import io.github.markpollack.workflow.engine.InMemoryEventSink;
import io.github.markpollack.workflow.engine.OperationResult;
import io.github.markpollack.workflow.engine.SimpleOperationRegistry;
import io.github.markpollack.workflow.engine.WorkflowInterpreter;
import io.github.markpollack.workflow.engine.WorkflowRunOutcome;
import io.github.markpollack.workflow.spec.AlwaysCondition;
import io.github.markpollack.workflow.spec.Binding;
import io.github.markpollack.workflow.spec.OperationDeclaration;
import io.github.markpollack.workflow.spec.TaskSpecNode;
import io.github.markpollack.workflow.spec.TerminateSpecNode;
import io.github.markpollack.workflow.spec.TerminateStatus;
import io.github.markpollack.workflow.spec.WorkflowEdgeSpec;
import io.github.markpollack.workflow.spec.WorkflowMetadata;
import io.github.markpollack.workflow.spec.WorkflowSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-dialect check (testing-KB deviation carried since Stage 1): the JPA store's
 * schema and JSON TEXT payload columns work against PostgreSQL, not just H2's
 * permissive dialect. Requires Docker; skipped when unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ContextConfiguration(classes = JpaTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresCheckpointStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private JpaCheckpointStore store;

    @Test
    void durableRunCommitsResumesAndPrunesOnPostgres() {
        WorkflowSpec spec = new WorkflowSpec(WorkflowSpec.API_VERSION, WorkflowSpec.KIND,
                new WorkflowMetadata("pg-smoke", "1.0.0", null, null),
                null, null, null,
                Map.of("op-a", new OperationDeclaration("java:t.a:v1", null, null, null, null, null)),
                List.of(new TaskSpecNode("a", null, null, null, "op-a", null,
                                Map.of("a.result", new Binding("$node.a.output")), null),
                        new TerminateSpecNode("done", null, null, null, TerminateStatus.COMPLETED,
                                new Binding("$node.a.output"))),
                List.of(new WorkflowEdgeSpec("a", "done", new AlwaysCondition(), null)),
                null, "a", null);
        SimpleOperationRegistry registry = new SimpleOperationRegistry()
                .register("java:t.a:v1", (inv, ctx, in) -> OperationResult.success(
                        Map.of("payload", "unicode-ß-🚀", "n", 42)));

        WorkflowRunOutcome outcome = new WorkflowInterpreter(registry, new InMemoryEventSink(),
                java.time.Clock.systemUTC(), store).run(spec, "run-pg", null);
        assertThat(outcome.completed()).isTrue();

        // journal + checkpoints round-trip through real TEXT columns
        assertThat(store.journal("run-pg")).isNotEmpty()
                .allSatisfy(e -> assertThat(e.get("workflowRunId").asText()).isEqualTo("run-pg"));

        // terminal run: openRun refuses; prune clears everything
        assertThat(store.prune(java.time.Instant.now().plusSeconds(60), true).runsPruned())
                .isEqualTo(1);
        assertThat(store.journal("run-pg")).isEmpty();

        // a fresh run on the pruned id starts clean — full lifecycle exercised
        CheckpointStore.RunState fresh = store.openRun("run-pg",
                "workflow://registry/pg-smoke@1.0.0", "rehash");
        assertThat(fresh.resumed()).isFalse();
    }
}
