package io.github.markpollack.workflow.spec;

import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * RFC 8785 (JSON Canonicalization Scheme) entry point for the project's canonical form
 * (DD-15). Cross-SDK equivalence is byte equality of this output. Number formatting
 * follows ECMAScript rules per the RFC (e.g. {@code 1.0} canonicalizes to {@code 1},
 * {@code 1e2} to {@code 100}).
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static byte[] canonicalize(byte[] utf8Json) {
        try {
            return new JsonCanonicalizer(new String(utf8Json, StandardCharsets.UTF_8)).getEncodedUTF8();
        } catch (IOException e) {
            throw new UncheckedIOException("input is not valid JSON", e);
        }
    }

    public static String canonicalize(String json) {
        try {
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (IOException e) {
            throw new UncheckedIOException("input is not valid JSON", e);
        }
    }
}
