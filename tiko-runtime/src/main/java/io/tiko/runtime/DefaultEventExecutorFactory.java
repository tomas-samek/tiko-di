package io.tiko.runtime;

import java.time.Duration;
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
     * @throws IllegalArgumentException if the effective max pool size is less than the effective core size
     */
    public static ExecutorService create(
            int queueCapacity, OverflowPolicy overflowPolicy, int coreSize, int maxSize, Duration keepAlive) {
        int cores = Runtime.getRuntime().availableProcessors();
        int corePoolSize = coreSize == TikoOptions.UNSET_POOL_SIZE ? Math.max(2, cores / 2) : coreSize;
        int maxPoolSize = maxSize == TikoOptions.UNSET_POOL_SIZE ? cores * 4 : maxSize;
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("eventExecutorMaxSize (" + maxPoolSize
                    + ") must be >= eventExecutorCoreSize (" + corePoolSize + ")");
        }
        long keepAliveMs =
                keepAlive == null ? TimeUnit.SECONDS.toMillis(DEFAULT_KEEP_ALIVE_SECONDS) : keepAlive.toMillis();

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tiko-event-async-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveMs,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                threadFactory,
                rejectionHandlerFor(overflowPolicy));
    }

    private static RejectedExecutionHandler rejectionHandlerFor(OverflowPolicy policy) {
        // CALLER_RUNS keeps its dedicated handler (#346); BLOCK/DROP/THROW/ROUTE_TO_DLQ share OverflowRejectionHandler.
        return policy == OverflowPolicy.CALLER_RUNS
                ? new ShutdownAwareCallerRunsPolicy()
                : new OverflowRejectionHandler(policy);
    }
}
