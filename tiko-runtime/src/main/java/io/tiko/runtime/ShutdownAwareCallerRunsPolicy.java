package io.tiko.runtime;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Rejection handler for the async event executor (#346). Behaves like
 * {@link ThreadPoolExecutor.CallerRunsPolicy} while the pool is live — under sustained
 * overload the submitting thread runs the task itself, providing backpressure — but when
 * the pool is shutting down it logs a WARNING instead of discarding the task silently.
 *
 * <p>It deliberately does not throw: a rejection at shutdown lands on whatever thread
 * published the event (possibly the shutdown thread or a transport consumer thread), and a
 * thrown exception there would break that thread's flow mid-teardown. The dropped event is
 * made observable through the log; delivery is not guaranteed once shutdown has begun.
 */
final class ShutdownAwareCallerRunsPolicy implements RejectedExecutionHandler {

    /** Lazy holder: defers System.LoggerFinder resolution until the first rejection. */
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            // Container is tearing down — drop observably rather than silently, and never throw.
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "Async event task rejected because the event executor is shutting down; the event was dropped.");
            return;
        }
        // Live pool, queue saturated: run on the caller for backpressure (CallerRunsPolicy).
        r.run();
    }
}
