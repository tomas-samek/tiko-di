package io.tiko;

/**
 * Root of the framework-originated runtime failures Tiko throws when <em>it</em> — not user code —
 * is the source of the error: a bean that was never registered, a container that failed to boot, a
 * lookup after shutdown. Callers can {@code catch (TikoException)} to handle any such failure at a
 * boundary, or catch a concrete subtype ({@link NoSuchComponentException},
 * {@link ContainerInitializationException}, {@link ContainerShutDownException},
 * {@link NoActiveEventScopeException}) for a specific case.
 *
 * <p>This is the thrown-side counterpart to the observer-side {@link ErrorContext}: both are sealed,
 * so the set of framework failure kinds is closed and adding one is a deliberate, compile-time-loud
 * change. The hierarchy deliberately excludes exceptions <em>thrown by user code</em> (a
 * {@code @Produces} method, a {@code @PostConstruct} hook) — those are propagated as-is so callers
 * see their original type.
 */
public abstract sealed class TikoException extends RuntimeException
        permits NoSuchComponentException,
                ContainerInitializationException,
                ContainerShutDownException,
                NoActiveEventScopeException {

    protected TikoException(String message) {
        super(message);
    }

    protected TikoException(String message, Throwable cause) {
        super(message, cause);
    }
}
