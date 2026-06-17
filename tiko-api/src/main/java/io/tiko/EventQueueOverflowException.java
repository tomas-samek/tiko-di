package io.tiko;

import java.util.concurrent.RejectedExecutionException;

/**
 * Thrown from {@code EventBus.publish(...)} on the publisher's thread when an asynchronous
 * dispatch is rejected because the event executor's bounded queue is full and the configured
 * overflow policy is {@code THROW} (#109).
 *
 * <p>Extends {@link RejectedExecutionException} so it integrates with the executor's standard
 * rejection mechanism and so callers already catching {@code RejectedExecutionException} keep
 * working; the dedicated type lets callers that care distinguish queue overflow from other
 * rejections.
 */
public class EventQueueOverflowException extends RejectedExecutionException {

    public EventQueueOverflowException(String message) {
        super(message);
    }
}
