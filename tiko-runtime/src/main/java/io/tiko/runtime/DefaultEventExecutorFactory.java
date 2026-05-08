package io.tiko.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
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
 *   <li>Queue: bounded {@link LinkedBlockingQueue} with capacity 1024</li>
 *   <li>Rejection policy: {@link ThreadPoolExecutor.CallerRunsPolicy} — under sustained
 *       overload the publisher thread runs the rejected task itself, providing
 *       backpressure rather than dropping events.</li>
 *   <li>Threads: daemon, named {@code tiko-event-async-{n}}.</li>
 * </ul>
 *
 * <p>Sized for typical small-to-medium services. Workloads with extreme throughput
 * or latency requirements should supply their own executor.
 */
public final class DefaultEventExecutorFactory {

    private DefaultEventExecutorFactory() {}

    public static ExecutorService create() {
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
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
