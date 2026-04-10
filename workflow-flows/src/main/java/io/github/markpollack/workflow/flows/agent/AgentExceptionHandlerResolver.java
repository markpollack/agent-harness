package io.github.markpollack.workflow.flows.agent;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves and invokes {@link ExceptionHandler @ExceptionHandler} methods for agent exceptions.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>Per-agent handlers — {@code @ExceptionHandler} methods on the agent instance's class</li>
 *   <li>Cross-cutting handlers — {@code @ExceptionHandler} methods on registered {@code @AgentAdvice} instances</li>
 * </ol>
 * <p>
 * When multiple handlers match, the most specific exception type wins (subclass beats superclass).
 * <p>
 * Handler method signatures supported:
 * <ul>
 *   <li>{@code ReturnType method(ExceptionType ex)}</li>
 *   <li>{@code ReturnType method(ExceptionType ex, AgentContext ctx)}</li>
 * </ul>
 *
 * <pre>{@code
 * var resolver = new AgentExceptionHandlerResolver(List.of(globalAdvice));
 *
 * try {
 *     return agent.handle(ctx, input);
 * } catch (Exception ex) {
 *     return resolver.resolve(agent, ex, ctx)
 *         .orElseThrow(() -> ex);
 * }
 * }</pre>
 */
public final class AgentExceptionHandlerResolver {

    private static final Logger logger = LoggerFactory.getLogger(AgentExceptionHandlerResolver.class);

    private final List<Object> adviceInstances;

    /**
     * Creates a resolver with the given cross-cutting advice instances.
     *
     * @param adviceInstances objects whose classes have {@code @ExceptionHandler} methods
     */
    public AgentExceptionHandlerResolver(List<?> adviceInstances) {
        this.adviceInstances = List.copyOf(
                Objects.requireNonNull(adviceInstances, "adviceInstances must not be null"));
    }

    /**
     * Creates a resolver with no cross-cutting advice (per-agent handlers only).
     */
    public AgentExceptionHandlerResolver() {
        this(List.of());
    }

    /**
     * Attempts to resolve and invoke an exception handler for the given exception.
     * <p>
     * Tries per-agent handlers first, then cross-cutting advice. Returns the handler's
     * return value if a handler is found, or {@link Optional#empty()} if no handler matches.
     *
     * @param agentInstance the agent that threw the exception
     * @param ex            the exception to handle
     * @param ctx           the execution context
     * @return the handler result, or empty if no handler matched
     */
    public Optional<Object> resolve(Object agentInstance, Exception ex, AgentContext ctx) {
        // 1. Per-agent handlers (on the agent instance itself)
        Optional<Object> result = tryHandlers(agentInstance, ex, ctx);
        if (result.isPresent()) {
            logger.debug("Per-agent handler on {} resolved {}", agentInstance.getClass().getSimpleName(),
                    ex.getClass().getSimpleName());
            return result;
        }

        // 2. Cross-cutting advice handlers
        for (Object advice : adviceInstances) {
            result = tryHandlers(advice, ex, ctx);
            if (result.isPresent()) {
                logger.debug("Cross-cutting handler on {} resolved {}", advice.getClass().getSimpleName(),
                        ex.getClass().getSimpleName());
                return result;
            }
        }

        return Optional.empty();
    }

    /**
     * Validates that exception handler return types are compatible with the given
     * expected output type. Logs warnings for mismatches at startup time.
     *
     * @param agentInstance the agent to validate
     * @param expectedType  the expected output type of the agent
     */
    public void validateReturnTypes(Object agentInstance, Class<?> expectedType) {
        validateReturnTypesOn(agentInstance, expectedType, "agent");
        for (Object advice : adviceInstances) {
            validateReturnTypesOn(advice, expectedType, "advice");
        }
    }

    private void validateReturnTypesOn(Object instance, Class<?> expectedType, String scope) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);
            if (ann == null) continue;

            Class<?> returnType = method.getReturnType();
            if (returnType != void.class && !expectedType.isAssignableFrom(returnType)
                    && !Object.class.equals(expectedType)) {
                logger.warn("@ExceptionHandler method {}.{}() returns {} but {} output type is {} — "
                                + "possible ClassCastException at runtime",
                        instance.getClass().getSimpleName(), method.getName(),
                        returnType.getSimpleName(), scope,
                        expectedType.getSimpleName());
            }
        }
    }

    // -- Internal --

    private Optional<Object> tryHandlers(Object instance, Exception ex, AgentContext ctx) {
        List<HandlerCandidate> candidates = new ArrayList<>();

        for (Method method : instance.getClass().getDeclaredMethods()) {
            ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);
            if (ann == null) continue;

            Class<? extends Throwable>[] handledTypes = ann.value();
            if (handledTypes.length == 0) {
                // Infer from first parameter
                Class<?>[] params = method.getParameterTypes();
                if (params.length > 0 && Throwable.class.isAssignableFrom(params[0])) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Throwable> inferred = (Class<? extends Throwable>) params[0];
                    handledTypes = new Class[]{inferred};
                } else {
                    continue; // Can't determine exception type
                }
            }

            for (Class<? extends Throwable> handledType : handledTypes) {
                if (handledType.isInstance(ex)) {
                    candidates.add(new HandlerCandidate(method, instance, handledType));
                }
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Most specific exception type wins
        candidates.sort(Comparator.comparingInt(c -> -depth(c.exceptionType())));
        HandlerCandidate best = candidates.get(0);

        return Optional.of(invoke(best.method(), best.instance(), ex, ctx));
    }

    private Object invoke(Method method, Object instance, Exception ex, AgentContext ctx) {
        method.setAccessible(true);
        try {
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 2 && AgentContext.class.isAssignableFrom(paramTypes[1])) {
                return method.invoke(instance, ex, ctx);
            } else {
                return method.invoke(instance, ex);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException("Exception handler failed", cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot invoke exception handler: " + method.getName(), e);
        }
    }

    private static int depth(Class<?> type) {
        int d = 0;
        Class<?> c = type;
        while (c != null) {
            d++;
            c = c.getSuperclass();
        }
        return d;
    }

    private record HandlerCandidate(Method method, Object instance, Class<? extends Throwable> exceptionType) {}
}
