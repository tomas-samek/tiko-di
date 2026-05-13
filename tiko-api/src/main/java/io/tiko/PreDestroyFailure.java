package io.tiko;

/**
 * Error context raised when a {@code @PreDestroy} method throws during scope teardown or
 * container shutdown.
 *
 * <p>Pre-destroy failures do <em>not</em> halt teardown — the framework logs, routes the
 * failure through {@link ErrorHandler#onError(ErrorContext)}, then continues invoking the
 * remaining {@code @PreDestroy} hooks (LIFO order). This matches the
 * everything-must-clean-up contract: one bean's bad release should not strand the others.
 *
 * @param component the bean class whose {@code @PreDestroy} threw
 * @param cause     the throwable thrown by the {@code @PreDestroy} method
 */
public record PreDestroyFailure(Class<?> component, Throwable cause) implements ErrorContext {}
