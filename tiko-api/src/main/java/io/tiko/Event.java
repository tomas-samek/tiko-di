package io.tiko;

import java.time.Instant;
import java.util.Arrays;
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
 * <p><strong>Bound:</strong> an event holds itself and at most its 64 most recent ancestors. A
 * chain up to that length is kept exactly as it was built. In a longer one, such as a handler that
 * publishes the event it handles again and again, the oldest ancestors drop off, so the history a
 * long-running loop keeps reachable stays bounded: {@link #getRoot()} and {@link #getOriginChain()}
 * then start at the oldest ancestor still held, not at the event that began the loop. An ancestor
 * that is kept stays equal to the event it was: same payload, id and timestamp.</p>
 *
 * @param <T> the type of the event payload
 */
public final class Event<T> {

    /** How many ancestors an event holds at most (#730); see the class Javadoc. */
    static final int MAX_ANCESTORS = 64;

    private final T payload;
    private final Event<?> origin;
    private final int depth;
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
        // One more ancestor than the bound allows: link to the origin without its oldest ancestor.
        this.origin = origin == null || origin.depth < MAX_ANCESTORS ? origin : origin.withAncestors(MAX_ANCESTORS - 1);
        this.depth = this.origin == null ? 0 : this.origin.depth + 1;
        this.timestamp = Instant.now();
        this.eventId = java.util.UUID.randomUUID().toString();
    }

    /** The event {@code source} was, on top of another origin: it keeps the payload, timestamp and id. */
    private Event(Event<T> source, Event<?> origin) {
        this.payload = source.payload;
        this.origin = origin;
        this.depth = origin == null ? 0 : origin.depth + 1;
        this.timestamp = source.timestamp;
        this.eventId = source.eventId;
    }

    /**
     * This event with only its {@code count} nearest ancestors. The originals are shared with
     * other chains and cannot be cut, so this event and those ancestors are copied; the oldest copy
     * has no origin, which lets everything older be collected.
     */
    private Event<T> withAncestors(int count) {
        Event<?>[] nearest = new Event<?>[count];
        Event<?> ancestor = this.origin;
        for (int i = 0; i < count; i++) {
            nearest[i] = ancestor;
            ancestor = ancestor.origin;
        }
        Event<?> copy = null;
        for (int i = count - 1; i >= 0; i--) {
            copy = nearest[i].onTopOf(copy);
        }
        return onTopOf(copy);
    }

    private Event<T> onTopOf(Event<?> newOrigin) {
        return new Event<>(this, newOrigin);
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
     * Returns the origin chain from root to this event.
     *
     * <p>The list starts with the root event and ends with this event's payload.
     * Example: [OrderCreatedEvent, OrderValidatedEvent, PaymentProcessedEvent]</p>
     *
     * <p>The root is the oldest ancestor this event still holds; see the bound in the class
     * Javadoc.</p>
     *
     * @return immutable list of all event payloads in the chain
     */
    public List<Object> getOriginChain() {
        Object[] chain = new Object[depth + 1];
        Event<?> current = this;
        for (int i = depth; i >= 0; i--) {
            chain[i] = current.payload;
            current = current.origin;
        }
        return Collections.unmodifiableList(Arrays.asList(chain));
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
        return depth;
    }

    /**
     * Returns the root event of the chain: the oldest ancestor this event still holds.
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
