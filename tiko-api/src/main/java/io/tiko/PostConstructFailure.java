package io.tiko;

/**
 * Error context raised when a {@code @PostConstruct} method throws.
 *
 * <p>The framework calls {@link ErrorHandler#onError(ErrorContext)} before re-throwing the
 * cause, so observability code sees the failure even though the container continues to
 * propagate the exception (preserving the hard-fail contract on lifecycle hooks). The
 * concrete propagation site depends on the bean's scope: SINGLETON bubbles out of
 * {@code Tiko.create(...)}, while REQUEST/EVENT/PROTOTYPE bubbles out of the
 * {@code container.get(...)} call that triggered lazy initialization.
 *
 * @param component the bean class whose {@code @PostConstruct} threw
 * @param cause     the throwable thrown by the {@code @PostConstruct} method
 */
public record PostConstructFailure(Class<?> component, Throwable cause) implements ErrorContext {}
