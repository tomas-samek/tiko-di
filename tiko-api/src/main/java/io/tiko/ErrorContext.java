package io.tiko;

/**
 * Sealed root of all error categories surfaced through {@link ErrorHandler}.
 * Pattern-match on the concrete subtype to handle each category structurally:
 *
 * <pre>{@code
 * public void onError(ErrorContext ctx) {
 *     switch (ctx) {
 *         case EventHandlerError e     -> metrics.eventHandlerError(e.handler());
 *         case EventDispatchRejected r -> metrics.eventDropped(r.event());
 *         case PostConstructFailure f  -> metrics.lifecycleError("init", f.component());
 *         case PreDestroyFailure f     -> metrics.lifecycleError("teardown", f.component());
 *         case AutoCloseFailure f      -> metrics.lifecycleError("close", f.component());
 *         case ConfigurationFailure f  -> metrics.configError(f.issues().size());
 *         case ProduceFailure f        -> metrics.factoryError(f.declaringClass(), f.methodName());
 *         case TransportError t        -> metrics.transportError(t.transport(), t.cause());
 *     }
 * }
 * }</pre>
 *
 * <p>{@link EventHandlerError} is in-process / handler-side errors raised by the local
 * {@code EventBus}. {@link EventDispatchRejected} is the delivery-side counterpart — an async
 * dispatch rejected by queue overflow under the {@code ROUTE_TO_DLQ} policy, where no handler
 * ran. {@link PostConstructFailure}, {@link PreDestroyFailure} and
 * {@link AutoCloseFailure} are lifecycle-hook failures — annotation-driven for the first
 * two, implicit {@code close()} dispatch for the third. {@link ConfigurationFailure}
 * bundles every {@code @Configuration} binding problem from a single
 * {@code Tiko.create(...)} call. {@link ProduceFailure} is a {@code @Produces} factory
 * method that threw — the framework publishes this before re-throwing the original cause
 * via sneaky-throw so the consumer of {@code container.get(...)} sees the user's
 * exception unchanged. {@link TransportError} is the non-sealed permit every transport
 * module ({@code tiko-kafka}, future {@code tiko-http}, ...) extends to surface its own
 * concrete error types without forcing a tiko-api update.
 *
 * <p>Adding a new top-level permit here is intentionally a compile-time-loud breaking
 * change for users with exhaustive {@code switch} expressions — they are told to handle
 * the new category.
 */
public sealed interface ErrorContext
        permits EventHandlerError,
                EventDispatchRejected,
                PostConstructFailure,
                PreDestroyFailure,
                AutoCloseFailure,
                ConfigurationFailure,
                TransportError,
                ProduceFailure {

    /**
     * The throwable that caused this error context to be raised.
     */
    Throwable cause();
}
