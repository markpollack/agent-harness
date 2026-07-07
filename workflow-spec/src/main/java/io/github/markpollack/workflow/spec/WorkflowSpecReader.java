package io.github.markpollack.workflow.spec;

import java.io.InputStream;

/**
 * Entry point for externally authored specs (Python/TS-emitted JSON, files, registries).
 *
 * <p>Contract: {@code read} applies both validation phases — JSON Schema (wire shape)
 * and semantic validation (graph rules) — before returning; a returned
 * {@link WorkflowSpec} is by construction valid. Failures throw
 * {@link WorkflowSpecValidationException} carrying the same stable error codes the
 * Conformance Kit fixture corpus pins.
 */
public interface WorkflowSpecReader {

    WorkflowSpec read(InputStream json);
}
