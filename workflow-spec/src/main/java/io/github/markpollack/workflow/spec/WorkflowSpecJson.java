package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared Jackson configuration for the spec wire format. NON_NULL inclusion implements
 * DD-15 rule 2 (the wire never carries {@code null}; absent is the only empty state).
 * Strict duplicate-key detection is the Java-side enforcement of the duplicate
 * contextWrites/binding-target rules (semantic-rules SEM-07/SEM-14): duplicate keys in
 * the raw JSON are a parse error, never silently last-wins.
 */
final class WorkflowSpecJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private WorkflowSpecJson() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
