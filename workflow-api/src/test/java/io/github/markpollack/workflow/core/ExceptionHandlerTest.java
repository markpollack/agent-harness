package io.github.markpollack.workflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("@ExceptionHandler")
class ExceptionHandlerTest {

    static class HandlerHolder {
        @ExceptionHandler(IllegalArgumentException.class)
        public String handleIAE(IllegalArgumentException ex) {
            return "handled";
        }

        @ExceptionHandler({IllegalStateException.class, NullPointerException.class})
        public String handleMultiple(RuntimeException ex) {
            return "handled";
        }

        @ExceptionHandler
        public String handleInferred(ArithmeticException ex) {
            return "handled";
        }

        public String notAHandler() {
            return "not";
        }
    }

    @Test
    void shouldBeRetainedAtRuntime() throws NoSuchMethodException {
        Method method = HandlerHolder.class.getDeclaredMethod("handleIAE", IllegalArgumentException.class);
        ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);

        assertThat(ann).isNotNull();
    }

    @Test
    void shouldReturnDeclaredExceptionTypes() throws NoSuchMethodException {
        Method method = HandlerHolder.class.getDeclaredMethod("handleIAE", IllegalArgumentException.class);
        ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);

        assertThat(ann.value()).containsExactly(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportMultipleExceptionTypes() throws NoSuchMethodException {
        Method method = HandlerHolder.class.getDeclaredMethod("handleMultiple", RuntimeException.class);
        ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);

        assertThat(ann.value()).containsExactly(IllegalStateException.class, NullPointerException.class);
    }

    @Test
    void shouldDefaultToEmptyWhenNoValueSpecified() throws NoSuchMethodException {
        Method method = HandlerHolder.class.getDeclaredMethod("handleInferred", ArithmeticException.class);
        ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);

        assertThat(ann.value()).isEmpty();
    }

    @Test
    void shouldReturnNullWhenAbsent() throws NoSuchMethodException {
        Method method = HandlerHolder.class.getDeclaredMethod("notAHandler");
        ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);

        assertThat(ann).isNull();
    }
}
