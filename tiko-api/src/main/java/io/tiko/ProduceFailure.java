package io.tiko;

/**
 * Error context raised when a {@code @Produces} factory method throws.
 *
 * <p>The framework calls {@link ErrorHandler#onError(ErrorContext)} before
 * re-throwing the cause via sneaky-throw, so observability code sees the
 * failure even though the original throwable continues to propagate (with
 * its type and stack trace intact) to the {@code container.get(...)}
 * caller. Same hard-fail contract as {@link PostConstructFailure}.
 *
 * @param declaringClass the class that declares the {@code @Produces} method
 * @param methodName     the simple method name of the {@code @Produces}
 *     factory (one factory class may carry multiple, qualifier-disambiguated
 *     factories — the qualifier itself is reachable via the method's
 *     {@code @Produces(name=...)}; we don't duplicate it here)
 * @param cause          the throwable thrown by the factory method
 */
public record ProduceFailure(Class<?> declaringClass, String methodName, Throwable cause) implements ErrorContext {}
