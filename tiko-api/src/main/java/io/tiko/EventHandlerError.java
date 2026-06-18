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

    /**
     * Classifies this failure (#111), derived from {@link #cause()} and {@link #attempts()} — no
     * extra state. A dead-letter {@link ErrorHandler} can branch on the result rather than
     * inspecting the cause type by hand:
     *
     * <ul>
     *   <li>{@link DeliveryFailureKind#TIMEOUT} when the cause is a
     *       {@link java.util.concurrent.TimeoutException} (#107) — takes precedence, since it
     *       describes how the terminal attempt failed;</li>
     *   <li>{@link DeliveryFailureKind#EXHAUSTED} when more than one attempt ran (a retry budget
     *       was spent, #108);</li>
     *   <li>{@link DeliveryFailureKind#EXCEPTION} otherwise — a single failed attempt.</li>
     * </ul>
     *
     * @return the failure classification, never {@code null}
     */
    public DeliveryFailureKind kind() {
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return DeliveryFailureKind.TIMEOUT;
        }
        return attempts > 1 ? DeliveryFailureKind.EXHAUSTED : DeliveryFailureKind.EXCEPTION;
    }
}
