package io.github.markpollack.workflow.spec;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/** Default writer: Jackson serialization (nulls omitted) piped through RFC 8785 JCS. */
public final class DefaultWorkflowSpecWriter implements WorkflowSpecWriter {

    @Override
    public void write(WorkflowSpec spec, OutputStream out) {
        try {
            byte[] plain = WorkflowSpecJson.mapper().writeValueAsBytes(spec);
            out.write(CanonicalJson.canonicalize(plain));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("spec is not serializable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing canonical spec", e);
        }
    }
}
