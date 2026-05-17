package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies AggregatingContainer.shutdown() honours the configured shutdownTimeout (#48).
 * Two scenarios:
 *
 * <ul>
 *   <li>Forced path: a long-running task plus a tight timeout → executor forced via
 *       shutdownNow() and isTerminated() goes true within ~3x the configured timeout.</li>
 *   <li>Graceful path: a quick task with the default 10s timeout → shutdown returns
 *       far below the 10s budget (no accidental "always wait the full window" regression).</li>
 * </ul>
 *
 * <p>The container resolves to StubContainer via {@code src/test/resources/META-INF/tiko/container.properties}
 * — it intentionally has no @Component code; only the executor shutdown path is exercised.
 */
class AggregatingContainerShutdownTimeoutTest {

    private static final ErrorHandler NOOP_ERROR_HANDLER = ctx -> {};

    @Test
    void shutdownForcesExecutorWhenTimeoutExceeded() throws Exception {
        EventBus bus = new LocalEventBus();
        AggregatingContainer container = new AggregatingContainer(
                bus, NOOP_ERROR_HANDLER, /* userEventExecutor= */ null, /* shutdownTimeout= */ Duration.ofMillis(50));

        // Submit a task that outlasts the timeout.
        container.getEventExecutor().submit(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });

        long startNanos = System.nanoTime();
        container.shutdown();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        // shutdownNow() unblocks the worker via interrupt; give it a brief window to actually exit.
        // The bound here (200ms) is well below the 500ms sleep — if the worker isn't terminated by then,
        // shutdownNow() did not fire.
        assertThat(container.getEventExecutor().awaitTermination(200, TimeUnit.MILLISECONDS))
                .isTrue();
        // Loose upper bound for CI jitter — well below the 500ms sleep, confirming shutdownNow fired.
        assertThat(elapsed).isLessThan(Duration.ofMillis(300));
    }

    @Test
    void shutdownReturnsPromptlyWhenExecutorDrainsWellWithinDefault() throws Exception {
        EventBus bus = new LocalEventBus();
        AggregatingContainer container = new AggregatingContainer(
                bus, NOOP_ERROR_HANDLER, /* userEventExecutor= */ null, /* shutdownTimeout= */ Duration.ofSeconds(10));

        container.getEventExecutor().submit(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });

        long startNanos = System.nanoTime();
        container.shutdown();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(container.getEventExecutor().isTerminated()).isTrue();
        // Guards against a regression where shutdown accidentally always waits the full budget.
        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
    }
}
