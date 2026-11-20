package io.tiko.events;

import java.time.Instant;

/**
 * Lifecycle event published when a unit of work starts: the EVENT scope, not a bus event.
 *
 * <p>Published once per unit, before any EVENT-scoped bean is created: for every
 * {@code runInEventScope} / {@code supplyInEventScope} (an HTTP request a route group opens a unit
 * for, a {@code 404} included), every consumed Kafka record and every attempt of an async handler.
 * Publishing a bus event does not publish it.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class UnitTracer {
 *     @EventHandler
 *     public void onUnitStarted(EventStartedEvent event) {
 *         logger.trace("Unit of work {} started", event.eventId());
 *         tracer.startSpan(event.eventId());
 *     }
 * }
 * }</pre>
 *
 * @param eventId   unique identifier of this unit of work
 * @param timestamp the instant when the unit of work started
 */
public record EventStartedEvent(String eventId, Instant timestamp) {}
