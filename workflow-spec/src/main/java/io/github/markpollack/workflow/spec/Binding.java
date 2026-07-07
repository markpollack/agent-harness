package io.github.markpollack.workflow.spec;

import java.util.Objects;

/**
 * A declared data binding: {@code { "from": "$input.url" }}. Valid source prefixes are
 * {@code $input}, {@code $context}, {@code $const}, {@code $node.<id>.output},
 * {@code $node.<id>.decision} (alpha spec §12). The full path grammar is pinned at the
 * Step 1.6 spec freeze; the schema enforces a loose prefix pattern until then.
 */
public record Binding(String from) {

    public Binding {
        Objects.requireNonNull(from, "from");
        if (!from.startsWith("$")) {
            throw new IllegalArgumentException("binding source must start with '$': " + from);
        }
    }
}
