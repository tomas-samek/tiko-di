package io.tiko.runtime;

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
 *   <li>Core pool: {@code Math.max(2, availableProcessors() / 2)}</li>
 *   <li>Max pool: {@code availableProcessors() * 4}</li>
 *   <li>Keep-alive: 60 seconds</li>
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

    private DefaultEventExecutorFactory() {}

    public static ExecutorService create() {
        return create(DEFAULT_QUEUE_CAPACITY, OverflowPolicy.CALLER_RUNS);
    }

    /**
     * Builds the default event executor with a bounded queue of {@code queueCapacity} and the
     * given {@code overflowPolicy} for tasks rejected when that queue is full (#109).
     */
    public static ExecutorService create(int queueCapacity, OverflowPolicy overflowPolicy) {
        int cores = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(2, cores / 2);
        int maxPoolSize = cores * 4;

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
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                threadFactory,
                rejectionHandlerFor(overflowPolicy));
    }

    private static RejectedExecutionHandler rejectionHandlerFor(OverflowPolicy policy) {
        // CALLER_RUNS keeps its dedicated handler (#346); BLOCK/DROP/THROW share OverflowRejectionHandler.
        return policy == OverflowPolicy.CALLER_RUNS
                ? new ShutdownAwareCallerRunsPolicy()
                : new OverflowRejectionHandler(policy);
    }
}
