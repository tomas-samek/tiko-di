package io.tiko.events;

import java.time.Instant;

/**
 * Lifecycle event published when entering a request scope.
 *
 * <p>This event is automatically published when {@code container.runInRequestScope()}
 * is called, before any request-scoped beans are created.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class RequestMetrics {
 *     @EventHandler
 *     public void onRequestStarted(RequestStartedEvent event) {
 *         logger.debug("Request {} started", event.requestId());
 *         metrics.incrementActiveRequests();
 *     }
 * }
 * }</pre>
 *
 * @param requestId unique identifier for this request scope
 * @param timestamp the instant when the request scope was entered
 */
public record RequestStartedEvent(String requestId, Instant timestamp) {}
