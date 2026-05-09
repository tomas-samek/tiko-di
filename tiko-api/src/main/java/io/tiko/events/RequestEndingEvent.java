package io.tiko.events;

import java.time.Duration;
import java.time.Instant;

/**
 * Lifecycle event published when exiting a request scope.
 *
 * <p>This event is automatically published before {@code @PreDestroy} methods
 * are invoked on request-scoped beans (or, for beans that implement
 * {@link AutoCloseable} without an explicit {@code @PreDestroy}, before
 * {@code close()} runs). Useful for metrics, logging, and cleanup.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class RequestMetrics {
 *     @EventHandler
 *     public void onRequestEnding(RequestEndingEvent event) {
 *         logger.debug("Request {} completed in {}",
 *             event.requestId(), event.duration());
 *         metrics.recordRequestDuration(event.duration());
 *         metrics.decrementActiveRequests();
 *     }
 * }
 * }</pre>
 *
 * @param requestId unique identifier for this request scope
 * @param timestamp the instant when the request scope is exiting
 * @param duration  the duration of the request processing
 */
public record RequestEndingEvent(String requestId, Instant timestamp, Duration duration) {}
