package io.tiko.events;

import java.time.Duration;
import java.time.Instant;

/**
 * Lifecycle event published when exiting an event scope.
 *
 * <p>This event is automatically published before {@code @PreDestroy} methods
 * are invoked on event-scoped beans. Ideal for distributed tracing and metrics.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class EventTracer {
 *     @EventHandler
 *     public void onEventEnding(EventEndingEvent event) {
 *         logger.trace("Event {} processed in {}",
 *             event.eventId(), event.duration());
 *         tracer.finishSpan(event.eventId(), event.duration());
 *     }
 * }
 * }</pre>
 *
 * @param eventId   unique identifier for this event scope
 * @param timestamp the instant when the event scope is exiting
 * @param duration  the duration of the event processing
 */
public record EventEndingEvent(String eventId, Instant timestamp, Duration duration) {
}
