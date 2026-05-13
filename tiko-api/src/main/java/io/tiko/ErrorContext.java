package io.tiko;

/**
 * Sealed root of all error categories surfaced through {@link ErrorHandler}.
 * Pattern-match on the concrete subtype to handle each category structurally:
 *
 * <pre>{@code
 * public void onError(ErrorContext ctx) {
 *     switch (ctx) {
 *         case EventHandlerError e     -> metrics.eventHandlerError(e.handler());
 *         case PostConstructFailure f  -> metrics.lifecycleError("init", f.component());
 *         case PreDestroyFailure f     -> metrics.lifecycleError("teardown", f.component());
 *         case ConfigurationFailure f  -> metrics.configError(f.issues().size());
 *         case TransportError t        -> metrics.transportError(t.transport(), t.cause());
 *     }
 * }
 * }</pre>
 *
 * <p>{@link EventHandlerError} is in-process / handler-side errors raised by the local
 * {@code EventBus}. {@link PostConstructFailure} and {@link PreDestroyFailure} are
 * lifecycle-hook failures (annotation-driven). {@link ConfigurationFailure} bundles every
 * {@code @Configuration} binding problem from a single {@code Tiko.create(...)} call.
 * {@link TransportError} is the non-sealed permit every transport module ({@code tiko-kafka},
 * future {@code tiko-http}, ...) extends to surface its own concrete error types without
 * forcing a tiko-api update.
 *
 * <p>Adding a new top-level permit here is intentionally a compile-time-loud breaking
 * change for users with exhaustive {@code switch} expressions — they are told to handle
 * the new category.
 */
public sealed interface ErrorContext
        permits EventHandlerError, PostConstructFailure, PreDestroyFailure, ConfigurationFailure, TransportError {

    /**
     * The throwable that caused this error context to be raised.
     */
    Throwable cause();
}
