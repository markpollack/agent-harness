package io.github.markpollack.workflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("@Description")
class DescriptionTest {

    @Description("Analyzes a PR diff")
    static class AnnotatedClass {

        @Description("Does something specific")
        public void annotatedMethod() {}

        public void unannotatedMethod() {}
    }

    static class UnannotatedClass {}

    @Test
    void shouldBeRetainedAtRuntimeOnType() {
        Description ann = AnnotatedClass.class.getAnnotation(Description.class);

        assertThat(ann).isNotNull();
    }

    @Test
    void shouldReturnValueViaReflectionOnType() {
        Description ann = AnnotatedClass.class.getAnnotation(Description.class);

        assertThat(ann.value()).isEqualTo("Analyzes a PR diff");
    }

    @Test
    void shouldReturnNullWhenAbsentOnType() {
        Description ann = UnannotatedClass.class.getAnnotation(Description.class);

        assertThat(ann).isNull();
    }

    @Test
    void shouldBeRetainedAtRuntimeOnMethod() throws NoSuchMethodException {
        Method method = AnnotatedClass.class.getMethod("annotatedMethod");
        Description ann = method.getAnnotation(Description.class);

        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("Does something specific");
    }

    @Test
    void shouldReturnNullWhenAbsentOnMethod() throws NoSuchMethodException {
        Method method = AnnotatedClass.class.getMethod("unannotatedMethod");
        Description ann = method.getAnnotation(Description.class);

        assertThat(ann).isNull();
    }
}
