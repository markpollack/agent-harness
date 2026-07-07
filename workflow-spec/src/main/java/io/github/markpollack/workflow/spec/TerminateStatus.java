package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Declared terminal status of a {@code terminate} node. */
public enum TerminateStatus {

    @JsonProperty("completed")
    COMPLETED,

    @JsonProperty("failed")
    FAILED,

    @JsonProperty("cancelled")
    CANCELLED,

    @JsonProperty("aborted")
    ABORTED
}
