package io.tiko.events;

import java.time.Instant;

/**
 * Lifecycle event published when entering an event scope.
 *
 * <p>This event is automatically published when {@code container.runInEventScope()}
 * is called, before any event-scoped beans are created.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class EventTracer {
 *     @EventHandler
 *     public void onEventStarted(EventStartedEvent event) {
 *         logger.trace("Event {} started", event.eventId());
 *         tracer.startSpan(event.eventId());
 *     }
 * }
 * }</pre>
 *
 * @param eventId   unique identifier for this event scope
 * @param timestamp the instant when the event scope was entered
 */
public record EventStartedEvent(String eventId, Instant timestamp) {
}
