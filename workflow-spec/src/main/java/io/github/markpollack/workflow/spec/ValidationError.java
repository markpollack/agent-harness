package io.github.markpollack.workflow.spec;

import java.util.Objects;

/**
 * One structured validation failure. {@code code} is a stable, fixture-pinned error code
 * (the cross-SDK contract); {@code path} is identifier-qualified where possible
 * (e.g. {@code nodes[id=route].outcomes}) rather than a bare index (DD-13).
 */
public record ValidationError(String code, String path, String message) {

    public ValidationError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
