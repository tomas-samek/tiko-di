package io.tiko;

/**
 * Error context raised when {@code AutoCloseable.close()} throws during scope teardown or
 * container shutdown. Applies to two distinct cases:
 *
 * <ul>
 *   <li>A component implementing {@link AutoCloseable} with no {@code @PreDestroy} methods —
 *       the framework calls {@code close()} as the implicit teardown.</li>
 *   <li>A {@code @Produces} factory-produced bean whose return type implements
 *       {@link AutoCloseable} — the framework closes it at scope exit even though the user
 *       never annotated a destroy hook.</li>
 * </ul>
 *
 * <p>Close failures do <em>not</em> halt teardown — the framework logs, routes the failure
 * through {@link ErrorHandler#onError(ErrorContext)}, then continues closing the remaining
 * beans (LIFO order), parallel to {@link PreDestroyFailure}. The two are kept as distinct
 * permits because the failure modes are semantically different: an annotated {@code @PreDestroy}
 * is user-authored teardown logic, while a thrown {@code close()} is typically a third-party
 * resource (data source, HTTP client, Kafka producer) misbehaving.
 *
 * @param component the runtime class whose {@code close()} threw
 * @param cause     the throwable thrown by {@code close()}
 */
public record AutoCloseFailure(Class<?> component, Throwable cause) implements ErrorContext {}
