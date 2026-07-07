package io.github.markpollack.workflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared Jackson configuration for engine-side wire shapes (event payload fragments;
 * later the full event/result wire projections frozen at Step 2.5). NON_NULL mirrors
 * the spec module's {@code WorkflowSpecJson}: the wire never carries {@code null} —
 * absent is the only empty state (DD-15 rule 2).
 */
final class WorkflowEventJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private WorkflowEventJson() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
