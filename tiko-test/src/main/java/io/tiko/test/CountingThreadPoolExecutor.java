package io.tiko.test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ThreadPoolExecutor} that tracks in-flight work by counting at <em>submit</em> time
 * rather than at execution time, so {@link RecordingEventBus#awaitAsyncDispatch(java.time.Duration)}
 * can detect drain deterministically.
 *
 * <p>Polling {@link #getActiveCount()} and {@link #getQueue()} is racy: a worker removes a task
 * from the queue (queue now empty) and only <em>then</em> locks itself (active count now non-zero),
 * so there is an instant where a submitted-but-unfinished task is invisible in both — the exact
 * window that made {@code AsyncHandlerTest} flake (#443). The in-flight counter closes that window:
 * it is incremented inside {@link #execute(Runnable)} — synchronously, before the task is even
 * enqueued — and decremented only after the task has run, so a task is never uncounted while it is
 * anywhere between submission and completion.
 *
 * <p>A submit rejected with a {@link java.util.concurrent.RejectedExecutionException} (an
 * {@code AbortPolicy} / THROW-shaped overflow) undoes its increment. The counter cannot see a
 * <em>silently</em> dropped task ({@code CallerRunsPolicy} after shutdown, {@code DiscardPolicy}),
 * which would leak — a non-issue in practice: the framework-defaults pool runs tasks on the caller
 * and is only shut down after the test, never mid-dispatch.
 *
 * <p>This lives in {@code tiko-test} and is installed by the {@code @TikoTest} extension via
 * {@link io.tiko.runtime.TikoOptions.Builder#eventExecutor(java.util.concurrent.ExecutorService)};
 * production dispatch keeps using the framework default pool.
 */
public class CountingThreadPoolExecutor extends ThreadPoolExecutor {

    private final AtomicLong inFlight = new AtomicLong();

    public CountingThreadPoolExecutor(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory,
            RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    /**
     * Builds a test event executor mirroring the framework default pool
     * ({@code io.tiko.runtime.DefaultEventExecutorFactory}): core {@code max(2, cores / 2)}, max
     * {@code cores * 4}, 60s keep-alive, a 1024-slot bounded queue, daemon threads, and a
     * caller-runs overflow policy. Fidelity to the default keeps {@code @TikoTest} async behavior
     * representative of production while adding deterministic drain detection.
     */
    public static CountingThreadPoolExecutor withFrameworkDefaults() {
        int cores = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(2, cores / 2);
        int maxPoolSize = cores * 4;
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tiko-event-async-test-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return new CountingThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                threadFactory,
                new CallerRunsPolicy());
    }

    /** Tasks submitted but not yet run to completion (queued + running). Never negative. */
    public long inFlight() {
        return inFlight.get();
    }

    @Override
    public void execute(Runnable command) {
        inFlight.incrementAndGet();
        try {
            super.execute(() -> {
                try {
                    command.run();
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RuntimeException | Error e) {
            // Rejected (queue full / shut down) or any submit-time failure: the wrapper never runs,
            // so undo the increment here to keep the count honest.
            inFlight.decrementAndGet();
            throw e;
        }
    }
}
