package io.tiko.runtime;

import io.tiko.EventQueueOverflowException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final long SHUTDOWN_CHECK_MILLIS = 50;

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
            logShutdownDrop();
            return;
        }
        switch (policy) {
            case BLOCK -> {
                switch (r) {
                    case BlockingTask retry when retry.rejectResubmission() -> {
                        // The original submission loop will retry when there is capacity.
                    }
                    case null, default -> enqueueBlocking(r, executor);
                }
            }
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
        var queued = new BlockingTask(r);
        try {
            while (!executor.isShutdown()) {
                // The queue wakes us as soon as space opens. Bound the wait so shutdown can
                // release the publisher even when no more tasks will be dequeued.
                if (executor.getQueue().offer(queued, SHUTDOWN_CHECK_MILLIS, TimeUnit.MILLISECONDS)
                        && finishEnqueue(queued, executor)) {
                    return;
                }
            }
            logShutdownDrop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "Interrupted while waiting for async event-queue space; the event was dropped.");
        }
    }

    private static boolean finishEnqueue(BlockingTask queued, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            // Shutdown can overlap a successful offer. Removal alone cannot distinguish a
            // task already claimed by a worker from one drained by shutdownNow().
            if (queued.discard()) {
                executor.remove(queued);
                logShutdownDrop();
            }
        } else if (executor.getPoolSize() == 0 && executor.remove(queued)) {
            // A zero-core pool can lose its last worker while space opens. Submit through
            // execute() in that case so the executor starts a worker for the task.
            return queued.tryExecute(executor);
        }
        return true;
    }

    private static void logShutdownDrop() {
        LoggerHolder.LOG.log(
                System.Logger.Level.WARNING,
                "Async event task rejected because the event executor is shutting down; the event was dropped.");
    }

    private static final class BlockingTask implements Runnable {
        private final AtomicReference<Runnable> pending;
        private Thread resubmitter;

        BlockingTask(Runnable task) {
            pending = new AtomicReference<>(task);
        }

        boolean discard() {
            return pending.getAndSet(null) != null;
        }

        boolean tryExecute(ThreadPoolExecutor executor) {
            resubmitter = Thread.currentThread();
            try {
                executor.execute(this);
                return resubmitter != null;
            } finally {
                resubmitter = null;
            }
        }

        boolean rejectResubmission() {
            if (resubmitter != Thread.currentThread()) {
                return false;
            }
            // Return to the original wait loop instead of nesting another rejection handler.
            resubmitter = null;
            return true;
        }

        @Override
        public void run() {
            var task = pending.getAndSet(null);
            if (task != null) {
                task.run();
            }
        }
    }
}
