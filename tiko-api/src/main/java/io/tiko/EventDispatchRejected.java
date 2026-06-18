package io.tiko;

/**
 * Error context raised when an asynchronous event dispatch is rejected because the framework
 * executor's bounded queue is full and the configured overflow policy is {@code ROUTE_TO_DLQ}
 * (#111, composing #109's backpressure).
 *
 * <p>Unlike {@link EventHandlerError}, <strong>no handler ran</strong> — the event never reached
 * dispatch — so there is no handler identity and no causing throwable ({@link #cause()} is
 * {@code null}). The event itself is carried so a dead-letter {@link ErrorHandler} can persist,
 * log, or replay it (e.g. {@code bus.publish(rejected.event())}).
 *
 * @param event the event whose async dispatch was rejected by queue overflow
 */
public record EventDispatchRejected(Object event) implements ErrorContext {

    /**
     * Always {@code null} — queue overflow is a delivery-side rejection, not a thrown failure.
     */
    @Override
    public Throwable cause() {
        return null;
    }
}
