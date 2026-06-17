package io.tiko;

/**
 * Classifies why an {@code @EventHandler} invocation ultimately failed (#111), as reported by
 * {@link EventHandlerError#kind()}. Lets a dead-letter {@link ErrorHandler} branch on the
 * failure mode without inspecting the cause or attempt count by hand.
 *
 * <p>Queue overflow is <em>not</em> a value here: no handler ran, so it is surfaced as the
 * distinct {@link EventDispatchRejected} permit rather than an {@link EventHandlerError}.
 */
public enum DeliveryFailureKind {

    /** The handler threw on its only attempt (no retries configured, or a sync handler). */
    EXCEPTION,

    /** The handler exceeded its {@code @EventHandler(timeout = ...)} budget (#107). */
    TIMEOUT,

    /** The handler exhausted its {@code @EventHandler(retries = ...)} budget (#108). */
    EXHAUSTED
}
