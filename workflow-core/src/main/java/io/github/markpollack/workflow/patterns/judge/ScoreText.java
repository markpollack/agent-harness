/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.patterns.judge;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * How a possibly-absent score reads to a person.
 *
 * <p>Once a score became an {@link OptionalDouble}, every log line and termination reason that
 * interpolated it started rendering {@code OptionalDouble[0.9]} — a Java wrapper's debug form
 * leaking into text a reader is meant to understand — and {@code OptionalDouble.empty} where the
 * reader is owed a word. Both are worse than what they replaced.
 *
 * <p>The rule lives here once rather than as a ternary at each site, because the two halves have
 * to stay consistent: a measurement always reads as a plain number, and an absence always reads as
 * an absence and never as a number.
 */
public final class ScoreText {

    private ScoreText() {
    }

    /**
     * Renders a score for a log line, a termination reason, or a prompt.
     *
     * @param score the score, which may be absent because nothing was measured
     * @return the score to two decimal places, or {@code "not measured"}
     */
    public static String describe(OptionalDouble score) {
        return score.isPresent() ? String.format(Locale.ROOT, "%.2f", score.getAsDouble()) : "not measured";
    }
}
