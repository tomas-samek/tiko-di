package io.tiko.events;

import java.time.Duration;
import java.time.Instant;

/**
 * Lifecycle event published when a unit of work ends: the EVENT scope, not a bus event.
 *
 * <p>Published once per unit, the counterpart of {@link EventStartedEvent}. It is published before {@code @PreDestroy} methods
 * are invoked on event-scoped beans (or, for beans that implement
 * {@link AutoCloseable} without an explicit {@code @PreDestroy}, before
 * {@code close()} runs). Ideal for distributed tracing and metrics.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class UnitTracer {
 *     @EventHandler
 *     public void onUnitEnding(EventEndingEvent event) {
 *         logger.trace("Unit of work {} took {}",
 *             event.eventId(), event.duration());
 *         tracer.finishSpan(event.eventId(), event.duration());
 *     }
 * }
 * }</pre>
 *
 * @param eventId   unique identifier of this unit of work, the one its {@link EventStartedEvent} carried
 * @param timestamp the instant when the unit of work is ending
 * @param duration  how long the unit of work ran
 */
public record EventEndingEvent(String eventId, Instant timestamp, Duration duration) {}
