package io.tiko;

/**
 * Error context raised when an {@code @EventHandler} method throws — sync or async.
 *
 * @param handler identifies which handler method threw
 * @param event   the event instance the handler was processing
 * @param cause   the throwable thrown by the handler ({@code CompletionException}
 *                already unwrapped to the user's original throwable)
 */
public record EventHandlerError(EventHandlerInfo handler, Object event, Throwable cause) implements ErrorContext {}
