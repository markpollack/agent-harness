package io.github.markpollack.workflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("@StepName")
class StepNameTest {

    @StepName("stable-key")
    static class AnnotatedClass {}

    static class UnannotatedClass {}

    @Test
    void shouldBeRetainedAtRuntime() {
        StepName ann = AnnotatedClass.class.getAnnotation(StepName.class);

        assertThat(ann).isNotNull();
    }

    @Test
    void shouldReturnValueViaReflection() {
        StepName ann = AnnotatedClass.class.getAnnotation(StepName.class);

        assertThat(ann.value()).isEqualTo("stable-key");
    }

    @Test
    void shouldReturnNullWhenAbsent() {
        StepName ann = UnannotatedClass.class.getAnnotation(StepName.class);

        assertThat(ann).isNull();
    }
}
