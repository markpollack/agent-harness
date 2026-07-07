package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared Jackson configuration for the spec wire format. NON_NULL inclusion implements
 * DD-15 rule 2 (the wire never carries {@code null}; absent is the only empty state).
 */
final class WorkflowSpecJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private WorkflowSpecJson() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
