package io.github.markpollack.workflow.spec;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A declared data binding: {@code { "from": "$input.url" }}. The v2-alpha binding path
 * grammar (alpha spec §12; extended at Increment 6): bare {@code $input} or
 * {@code $input.<key>}; {@code $item} or {@code $item.<key>} and {@code $itemIndex}
 * (fork fan-out — the same one-level access as {@code $input}, not attribute
 * navigation); {@code $context.}/{@code $const.} + a literal flat key (printable ASCII);
 * {@code $node.<id>.output} / {@code $node.<id>.decision} only — no attribute paths.
 */
public record Binding(String from) {

    private static final Pattern GRAMMAR = Pattern.compile(
            "\\$(input(\\.[A-Za-z0-9_-]+)?"
                    + "|item(\\.[A-Za-z0-9_-]+)?"
                    + "|itemIndex"
                    + "|context\\.[\\x21-\\x7E]+"
                    + "|const\\.[\\x21-\\x7E]+"
                    + "|node\\.[A-Za-z0-9_-]+\\.(output|decision))");

    public Binding {
        Objects.requireNonNull(from, "from");
        if (!from.startsWith("$")) {
            throw new IllegalArgumentException("binding source must start with '$': " + from);
        }
        if (!GRAMMAR.matcher(from).matches()) {
            throw new IllegalArgumentException(
                    "binding source does not match the v2-alpha grammar (alpha spec §12): " + from);
        }
    }
}
