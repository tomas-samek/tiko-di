package io.tiko;

/**
 * Sealed root of all error categories surfaced through {@link ErrorHandler}.
 * Pattern-match on the concrete subtype to handle each category structurally:
 *
 * <pre>{@code
 * public void onError(ErrorContext ctx) {
 *     switch (ctx) {
 *         case EventHandlerError e -> metrics.eventHandlerError(e.handler());
 *     }
 * }
 * }</pre>
 *
 * <p>Only {@link EventHandlerError} is permitted in this release. Future framework-error
 * categories (lifecycle, configuration, scope) will add new permits in follow-up PRs.
 * Adding a permit is intentionally a compile-time-loud breaking change for users with
 * exhaustive {@code switch} expressions — when a new category appears, callers are told
 * to handle it.
 */
public sealed interface ErrorContext permits EventHandlerError {

    /**
     * The throwable that caused this error context to be raised.
     */
    Throwable cause();
}
