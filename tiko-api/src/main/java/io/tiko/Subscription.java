package io.tiko;

/**
 * Represents a subscription to an event type.
 * Can be used to unsubscribe from future events.
 */
public interface Subscription {

    /**
     * Unsubscribes from the event stream.
     * After calling this method, the handler will no longer receive events.
     */
    void unsubscribe();

    /**
     * Checks if this subscription is still active.
     *
     * @return true if subscribed, false if unsubscribed
     */
    boolean isActive();
}
