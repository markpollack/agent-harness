package io.github.markpollack.workflow.engine;

import io.github.markpollack.workflow.spec.DefaultWorkflowSpecWriter;
import io.github.markpollack.workflow.spec.WorkflowSpec;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over the spec's canonical bytes (DD-15 / RFC 8785) — the run-identity pin.
 * Because cross-SDK equivalence is canonical byte equality, semantically identical
 * specs authored anywhere hash identically; any definition change changes the hash
 * and makes in-flight runs refuse resume.
 */
public final class CanonicalSpecHash {

    private CanonicalSpecHash() {
    }

    public static String of(WorkflowSpec spec) {
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        new DefaultWorkflowSpecWriter().write(spec, canonical);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
