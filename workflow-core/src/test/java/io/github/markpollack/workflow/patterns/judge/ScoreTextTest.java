package io.github.markpollack.workflow.patterns.judge;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreTextTest {

    @Test
    void scoreFormattingDoesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            assertThat(ScoreText.describe(OptionalDouble.of(0.75))).isEqualTo("0.75");
        } finally {
            Locale.setDefault(original);
        }
    }
}
