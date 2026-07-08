package io.github.markpollack.workflow.engine.jpa;

import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;

/** Shared @DataJpaTest wiring (workflow-batch idiom): auto-config + the store under test. */
@EnableAutoConfiguration
@EntityScan(basePackageClasses = WorkflowRunEntity.class)
class JpaTestConfig {

    @Bean
    JpaCheckpointStore checkpointStore(EntityManager em) {
        return new JpaCheckpointStore(em);
    }
}
