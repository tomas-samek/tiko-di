package io.tiko.events;

import java.time.Duration;
import java.time.Instant;

/**
 * Lifecycle event published when the container is about to shut down.
 *
 * <p>This event is automatically published before {@code @PreDestroy} methods
 * are invoked. Handlers can perform cleanup operations or logging.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class ShutdownHandler {
 *     @EventHandler
 *     public void onApplicationEnding(ApplicationEndingEvent event) {
 *         logger.info("Application ran for {}", event.uptime());
 *         // Cleanup resources
 *     }
 * }
 * }</pre>
 *
 * @param timestamp the instant when shutdown was initiated
 * @param uptime    the duration the application was running
 */
public record ApplicationEndingEvent(Instant timestamp, Duration uptime) {}
