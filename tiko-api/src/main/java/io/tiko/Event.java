package io.tiko;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Wrapper for events that tracks the full event origin chain.
 *
 * <p>This class is used internally by the framework to track event lineage when
 * using {@link io.tiko.annotations.EventTrigger}. User code typically works with unwrapped event
 * payloads, but can access the Event wrapper for tracing and debugging.</p>
 *
 * <p><strong>Event Chains:</strong> When an {@code @EventHandler} method with
 * {@code @EventTrigger} completes, the triggered event's origin is set to the
 * original event, creating a chain that can be traversed for debugging and
 * distributed tracing.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * // Event chain: OrderCreated -> OrderValidated -> PaymentProcessed
 *
 * @EventHandler
 * public void onPaymentProcessed(PaymentProcessedEvent event, Event<?> eventWrapper) {
 *     // Access origin chain
 *     List<Object> chain = eventWrapper.getOriginChain();
 *     // chain contains: [OrderCreatedEvent, OrderValidatedEvent, PaymentProcessedEvent]
 *
 *     // Find specific event in chain
 *     Optional<OrderCreatedEvent> original = eventWrapper.findInChain(OrderCreatedEvent.class);
 * }
 * }</pre>
 *
 * @param <T> the type of the event payload
 */
public final class Event<T> {

    private final T payload;
    private final Event<?> origin;
    private final Instant timestamp;
    private final String eventId;

    /**
     * Creates a new Event without origin (root event).
     *
     * @param payload the event payload
     */
    public Event(T payload) {
        this(payload, null);
    }

    /**
     * Creates a new Event with an origin (chained event).
     *
     * @param payload the event payload
     * @param origin  the parent event that triggered this event
     */
    public Event(T payload, Event<?> origin) {
        this.payload = Objects.requireNonNull(payload, "Event payload cannot be null");
        this.origin = origin;
        this.timestamp = Instant.now();
        this.eventId = java.util.UUID.randomUUID().toString();
    }

    /**
     * Returns the event payload.
     *
     * @return the payload
     */
    public T getPayload() {
        return payload;
    }

    /**
     * Returns the parent event that triggered this event.
     *
     * @return the origin event, or empty if this is a root event
     */
    public Optional<Event<?>> getOrigin() {
        return Optional.ofNullable(origin);
    }

    /**
     * Returns the timestamp when this event was created.
     *
     * @return the timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the unique identifier for this event.
     *
     * @return the event ID
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Returns the full origin chain from root to this event.
     *
     * <p>The list starts with the root event and ends with this event's payload.
     * Example: [OrderCreatedEvent, OrderValidatedEvent, PaymentProcessedEvent]</p>
     *
     * @return immutable list of all event payloads in the chain
     */
    public List<Object> getOriginChain() {
        List<Object> chain = new ArrayList<>();
        buildChain(this, chain);
        return Collections.unmodifiableList(chain);
    }

    private void buildChain(Event<?> event, List<Object> chain) {
        if (event.origin != null) {
            buildChain(event.origin, chain);
        }
        chain.add(event.payload);
    }

    /**
     * Finds the first event in the origin chain matching the specified type.
     *
     * <p>Searches from root to current event.</p>
     *
     * @param type the class of the event to find
     * @param <E>  the type of the event
     * @return the first matching event, or empty if not found
     */
    public <E> Optional<E> findInChain(Class<E> type) {
        return getOriginChain().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    /**
     * Returns the depth of this event in the chain (0 for root events).
     *
     * @return the chain depth
     */
    public int getChainDepth() {
        int depth = 0;
        Event<?> current = this.origin;
        while (current != null) {
            depth++;
            current = current.origin;
        }
        return depth;
    }

    /**
     * Returns the root event of the chain.
     *
     * @return the root event
     */
    public Event<?> getRoot() {
        Event<?> current = this;
        while (current.origin != null) {
            current = current.origin;
        }
        return current;
    }

    @Override
    public String toString() {
        return "Event{" + "payload="
                + payload.getClass().getSimpleName() + ", depth="
                + getChainDepth() + ", eventId='"
                + eventId + '\'' + ", timestamp="
                + timestamp + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Event<?> event)) return false;
        return eventId.equals(event.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }
}
