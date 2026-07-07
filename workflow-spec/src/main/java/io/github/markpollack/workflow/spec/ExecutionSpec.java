package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Declared execution characteristics of an operation. Alpha supports one mode. */
public record ExecutionSpec(ExecutionMode mode) {

    public ExecutionSpec {
        Objects.requireNonNull(mode, "mode");
    }

    /** Alpha execution modes. */
    public enum ExecutionMode {
        @JsonProperty("request-response")
        REQUEST_RESPONSE
    }
}
