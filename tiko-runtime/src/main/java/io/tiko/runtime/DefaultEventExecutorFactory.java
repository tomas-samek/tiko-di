package io.tiko.runtime;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the bounded {@link ThreadPoolExecutor} used for asynchronous event dispatch
 * when the user has not supplied a custom executor via {@code TikoOptions.eventExecutor(...)}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>Core pool: configurable (default {@code Math.max(2, availableProcessors() / 2)}, #110)</li>
 *   <li>Max pool: configurable (default {@code availableProcessors() * 4}, #110)</li>
 *   <li>Keep-alive: configurable (default 60 seconds, #110)</li>
 *   <li>Queue: bounded {@link LinkedBlockingQueue}, capacity configurable (default 1024, #109)</li>
 *   <li>Rejection policy: chosen by the {@link OverflowPolicy} (default {@code CALLER_RUNS}, #109).
 *       Every policy degrades to an observable WARNING (never a silent drop, never a hang, never a
 *       throw) for tasks rejected once the pool is shutting down (#346).</li>
 *   <li>Threads: daemon, named {@code tiko-event-async-{n}}.</li>
 * </ul>
 *
 * <p>Sized for typical small-to-medium services. Workloads with extreme throughput
 * or latency requirements should supply their own executor.
 */
public final class DefaultEventExecutorFactory {

    /** Historical defaults, applied when callers do not configure backpressure (#109). */
    static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /** Default non-core thread idle keep-alive (#110). */
    static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    private DefaultEventExecutorFactory() {}

    public static ExecutorService create() {
        return create(DEFAULT_QUEUE_CAPACITY, OverflowPolicy.CALLER_RUNS);
    }

    /**
     * Builds the default event executor with a bounded queue of {@code queueCapacity} and the
     * given {@code overflowPolicy} for tasks rejected when that queue is full (#109), using the
     * framework's processor-derived pool sizing.
     */
    public static ExecutorService create(int queueCapacity, OverflowPolicy overflowPolicy) {
        return create(queueCapacity, overflowPolicy, TikoOptions.UNSET_POOL_SIZE, TikoOptions.UNSET_POOL_SIZE, null);
    }

    /**
     * Builds the default event executor with explicit pool sizing (#110). A {@link
     * TikoOptions#UNSET_POOL_SIZE} core or max size, or a {@code null} keep-alive, falls back to
     * the framework's processor-derived defaults (core {@code Math.max(2, cores / 2)}, max {@code
     * cores * 4}, keep-alive {@value #DEFAULT_KEEP_ALIVE_SECONDS}s) — so omitting every #110 knob
     * reproduces the historical behaviour exactly.
     *
     * <p>Threads come from the booting container's {@link OwnedThreads} when one is bound to the
     * calling thread (the generated constructor and {@code start()} run bound, #634, #651), so
     * they are reported if they outlive {@code close()}; otherwise they are untracked. A bound
     * executor also schedules async retries on that owner's retry thread (#653).
     *
     * @throws IllegalArgumentException if the effective max pool size is less than the effective core size
     */
    public static ExecutorService create(
            int queueCapacity, OverflowPolicy overflowPolicy, int coreSize, int maxSize, Duration keepAlive) {
        return create(queueCapacity, overflowPolicy, coreSize, maxSize, keepAlive, OwnedThreads.boundOr(Thread::new));
    }

    /**
     * Builds the default event executor taking its threads from {@code threads}, renamed
     * {@code tiko-event-async-{n}} and made daemon (#634). The factory is captured here, once:
     * worker threads are started later, from whichever thread submits.
     */
    static ExecutorService create(
            int queueCapacity,
            OverflowPolicy overflowPolicy,
            int coreSize,
            int maxSize,
            Duration keepAlive,
            ThreadFactory threads) {
        int cores = Runtime.getRuntime().availableProcessors();
        int corePoolSize = coreSize == TikoOptions.UNSET_POOL_SIZE ? Math.max(2, cores / 2) : coreSize;
        int maxPoolSize = maxSize == TikoOptions.UNSET_POOL_SIZE ? cores * 4 : maxSize;
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("eventExecutorMaxSize (" + maxPoolSize
                    + ") must be >= eventExecutorCoreSize (" + corePoolSize + ")");
        }
        long keepAliveMs =
                keepAlive == null ? TimeUnit.SECONDS.toMillis(DEFAULT_KEEP_ALIVE_SECONDS) : keepAlive.toMillis();

        var rejectionHandler = rejectionHandlerFor(overflowPolicy);
        var counter = new AtomicInteger();
        ThreadFactory threadFactory = r -> {
            // An OverflowRejectionHandler needs to know its pool's workers: under BLOCK one of them
            // must not wait for queue space (#727).
            Thread t = threads.newThread(
                    rejectionHandler instanceof OverflowRejectionHandler overflow ? overflow.asWorker(r) : r);
            t.setName("tiko-event-async-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };

        if (threads instanceof OwnedThreads owner) {
            return new TrackedEventExecutor(
                    corePoolSize, maxPoolSize, keepAliveMs, queueCapacity, threadFactory, rejectionHandler, owner);
        }
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveMs,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                threadFactory,
                rejectionHandler);
    }

    /**
     * The default executor of a booted container: its workers come from the boot's
     * {@link OwnedThreads} (#634), and a retried async handler waits out its backoff on that
     * owner's retry thread (#653).
     */
    private static final class TrackedEventExecutor extends ThreadPoolExecutor implements RetryScheduling {
        private final OwnedThreads owner;

        TrackedEventExecutor(
                int corePoolSize,
                int maxPoolSize,
                long keepAliveMs,
                int queueCapacity,
                ThreadFactory threadFactory,
                RejectedExecutionHandler rejectionHandler,
                OwnedThreads owner) {
            super(
                    corePoolSize,
                    maxPoolSize,
                    keepAliveMs,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(queueCapacity),
                    threadFactory,
                    rejectionHandler);
            this.owner = owner;
        }

        @Override
        public boolean scheduleRetry(Runnable resubmit, long delayNanos) {
            return owner.scheduleRetry(resubmit, delayNanos);
        }

        @Override
        public boolean awaitDeliveries(long deadlineNanos) {
            return owner.awaitDeliveries(deadlineNanos);
        }

        @Override
        public int pendingDeliveries() {
            return owner.deliveries().count();
        }

        /** Counts the task as pending until it has run, been dropped, or been refused (#721). */
        @Override
        public void execute(Runnable command) {
            if (command instanceof OverflowRejectionHandler.BlockingTask) {
                super.execute(command); // a counted task the BLOCK handler offers again
                return;
            }
            var tracked = owner.deliveries().track(command);
            try {
                super.execute(tracked);
            } catch (RuntimeException refused) {
                tracked.dropped();
                throw refused;
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            var neverRun = super.shutdownNow();
            neverRun.forEach(PendingDeliveries::dropped);
            return neverRun;
        }
    }

    private static RejectedExecutionHandler rejectionHandlerFor(OverflowPolicy policy) {
        // CALLER_RUNS keeps its dedicated handler (#346); BLOCK/DROP/THROW/ROUTE_TO_DLQ share OverflowRejectionHandler.
        return policy == OverflowPolicy.CALLER_RUNS
                ? new ShutdownAwareCallerRunsPolicy()
                : new OverflowRejectionHandler(policy);
    }
}
