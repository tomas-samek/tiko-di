package io.tiko;

/**
 * Error context raised when an {@code @EventHandler} method throws — sync or async.
 *
 * @param handler  identifies which handler method threw
 * @param event    the event instance the handler was processing
 * @param cause    the throwable thrown by the handler ({@code CompletionException}
 *                 already unwrapped to the user's original throwable)
 * @param attempts the number of times the handler was invoked before this error was raised —
 *                 {@code 1} for a non-retrying handler, or the exhausted total for a handler with
 *                 {@code @EventHandler(retries = ...)} (#108)
 */
public record EventHandlerError(EventHandlerInfo handler, Object event, Throwable cause, int attempts)
        implements ErrorContext {

    /**
     * Single-attempt error — the common case (no retries). Delegates to the canonical constructor
     * with {@code attempts = 1}, so every existing call site and exhaustive {@code switch} over
     * {@link ErrorContext} keeps compiling.
     */
    public EventHandlerError(EventHandlerInfo handler, Object event, Throwable cause) {
        this(handler, event, cause, 1);
    }
}
