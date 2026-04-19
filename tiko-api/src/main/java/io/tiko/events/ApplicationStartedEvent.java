package io.tiko.events;

import java.time.Instant;

/**
 * Lifecycle event published when the container has completed initialization.
 *
 * <p>This event is automatically published after all singletons are created
 * and {@code @PostConstruct} methods have been invoked.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class StartupLogger {
 *     @EventHandler
 *     public void onApplicationStarted(ApplicationStartedEvent event) {
 *         logger.info("Application started at {}", event.timestamp());
 *     }
 * }
 * }</pre>
 *
 * @param timestamp the instant when the application started
 */
public record ApplicationStartedEvent(Instant timestamp) {
}
