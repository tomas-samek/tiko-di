package io.tiko.runtime;

import io.tiko.EventQueueOverflowException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Rejection handler for the {@code BLOCK}, {@code DROP}, {@code THROW}, and {@code ROUTE_TO_DLQ}
 * overflow policies of the default async event executor (#109, #111). The {@code CALLER_RUNS}
 * policy keeps its own {@link ShutdownAwareCallerRunsPolicy}; this handler covers the rest.
 *
 * <p>Like {@code ShutdownAwareCallerRunsPolicy}, it degrades to an observable, non-throwing logged
 * drop once the pool is shutting down: a rejection at shutdown lands on whatever thread published
 * the event, where blocking forever or throwing would break that thread's teardown flow (#346).
 */
final class OverflowRejectionHandler implements RejectedExecutionHandler {

    /** Lazy holder: defers System.LoggerFinder resolution until the first rejection. */
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    private final OverflowPolicy policy;

    OverflowRejectionHandler(OverflowPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            // Container is tearing down — drop observably rather than block forever or throw (#346).
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "Async event task rejected because the event executor is shutting down; the event was dropped.");
            return;
        }
        switch (policy) {
            case BLOCK -> enqueueBlocking(r, executor);
            case DROP ->
                LoggerHolder.LOG.log(
                        System.Logger.Level.WARNING,
                        "Async event queue is full; the event was dropped (overflow policy = DROP).");
            case THROW ->
                throw new EventQueueOverflowException("Async event queue is full and the overflow policy is THROW.");
            case ROUTE_TO_DLQ ->
                // Signal the dispatch site (which holds the event + ErrorHandler) to route an
                // EventDispatchRejected; the rejection handler itself sees neither.
                throw new DlqOverflowSignal("Async event queue is full; routing the event to the dead-letter handler.");
            default ->
                throw new IllegalStateException("OverflowRejectionHandler does not serve overflow policy " + policy);
        }
    }

    /** BLOCK: wait for queue space, applying backpressure to the publishing thread. */
    private static void enqueueBlocking(Runnable r, ThreadPoolExecutor executor) {
        try {
            executor.getQueue().put(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "Interrupted while waiting for async event-queue space; the event was dropped.");
        }
    }
}
