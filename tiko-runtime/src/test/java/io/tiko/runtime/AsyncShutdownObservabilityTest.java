package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Async events dropped because the container is shutting down must be observable, never
 * silent (#346). Two paths: the executor's rejection handler when a task is submitted to a
 * shutting-down pool (the race), and the generated dispatcher's stopped-gate (the common
 * post-shutdown publish, covered by an emission test in tiko-processor). The fix logs a
 * WARNING in both — it never throws (a shutdown-time throw would land on an arbitrary
 * publisher/teardown thread).
 */
class AsyncShutdownObservabilityTest {

    @BeforeEach
    void clearLogs() {
        CapturingLoggerFinder.clear();
    }

    private static ThreadPoolExecutor newPool() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    @Test
    void rejectionRunsOnCallerWhileExecutorIsLive() {
        ThreadPoolExecutor pool = newPool();
        try {
            AtomicBoolean ran = new AtomicBoolean(false);
            new ShutdownAwareCallerRunsPolicy().rejectedExecution(() -> ran.set(true), pool);
            assertThat(ran)
                    .as("a live-executor rejection must run on the caller (backpressure preserved)")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectionAfterShutdownIsLoggedNotRunNotThrown() {
        ThreadPoolExecutor pool = newPool();
        pool.shutdown();
        AtomicBoolean ran = new AtomicBoolean(false);

        // Must not throw, must not run the task on this thread (the container is tearing down).
        new ShutdownAwareCallerRunsPolicy().rejectedExecution(() -> ran.set(true), pool);

        assertThat(ran).isFalse();
        var records = CapturingLoggerFinder.RECORDS;
        assertThat(records)
                .anyMatch(r -> r.level() == System.Logger.Level.WARNING
                        && r.message().toLowerCase().contains("shut"));
    }

    @Test
    void droppedDuringShutdownHelperWarnsNamingTheEventType() {
        EventChainContext.logDroppedDuringShutdown(new DropPing());

        var records = CapturingLoggerFinder.RECORDS;
        assertThat(records)
                .anyMatch(r ->
                        r.level() == System.Logger.Level.WARNING && r.message().contains("DropPing"));
    }

    private record DropPing() {}
}
