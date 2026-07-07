package io.github.markpollack.workflow.spec;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A declared data binding: {@code { "from": "$input.url" }}. The frozen v2-alpha
 * binding path grammar (alpha spec §12): bare {@code $input} or {@code $input.<key>};
 * {@code $context.}/{@code $const.} followed by a literal flat key (printable ASCII);
 * {@code $node.<id>.output} / {@code $node.<id>.decision} only — no attribute paths.
 */
public record Binding(String from) {

    private static final Pattern GRAMMAR = Pattern.compile(
            "\\$(input(\\.[A-Za-z0-9_-]+)?"
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
