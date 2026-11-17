package io.tiko;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * An immutable point-in-time snapshot of the framework's event-dispatch executor (#110).
 *
 * <p>Obtained via {@link Container#eventExecutorMetrics()}. The values mirror the
 * corresponding {@link ThreadPoolExecutor} getters at the instant the snapshot was taken;
 * because the pool is live and concurrent, they are a consistent-enough sample for
 * monitoring (saturation, queue depth, throughput), not a transactional view.
 *
 * @param activeCount            threads actively executing tasks ({@link ThreadPoolExecutor#getActiveCount()})
 * @param poolSize               current number of threads in the pool ({@link ThreadPoolExecutor#getPoolSize()})
 * @param queueSize              tasks waiting in the work queue
 * @param queueRemainingCapacity free slots left in the bounded work queue before overflow
 * @param completedTaskCount     approximate count of tasks that have completed
 *                               ({@link ThreadPoolExecutor#getCompletedTaskCount()})
 * @param corePoolSize           configured core pool size ({@link ThreadPoolExecutor#getCorePoolSize()})
 * @param maxPoolSize            configured maximum pool size ({@link ThreadPoolExecutor#getMaximumPoolSize()})
 * @param pendingDeliveries      async event deliveries queued, running, or waiting out the backoff
 *                               before a retry ({@link Container#pendingAsyncDeliveries()}, #761)
 */
public record ExecutorMetrics(
        int activeCount,
        int poolSize,
        int queueSize,
        int queueRemainingCapacity,
        long completedTaskCount,
        int corePoolSize,
        int maxPoolSize,
        int pendingDeliveries) {

    /**
     * A snapshot without the count of pending deliveries, which reads {@code 0}: the shape this
     * record had before 0.8.0, kept for code compiled against it.
     */
    public ExecutorMetrics(
            int activeCount,
            int poolSize,
            int queueSize,
            int queueRemainingCapacity,
            long completedTaskCount,
            int corePoolSize,
            int maxPoolSize) {
        this(
                activeCount,
                poolSize,
                queueSize,
                queueRemainingCapacity,
                completedTaskCount,
                corePoolSize,
                maxPoolSize,
                0);
    }

    /**
     * Samples the given executor into an immutable snapshot, with {@code pendingDeliveries} at
     * {@code 0}: a container generated before 0.8.0 calls this.
     *
     * @param tpe the live framework executor to sample
     * @return a snapshot of {@code tpe}'s current state
     */
    public static ExecutorMetrics from(ThreadPoolExecutor tpe) {
        return from(tpe, 0);
    }

    /**
     * Samples the given executor into an immutable snapshot.
     *
     * @param tpe               the live framework executor to sample
     * @param pendingDeliveries what {@link Container#pendingAsyncDeliveries()} reports at this instant
     * @return a snapshot of {@code tpe}'s current state
     */
    public static ExecutorMetrics from(ThreadPoolExecutor tpe, int pendingDeliveries) {
        var queue = tpe.getQueue();
        return new ExecutorMetrics(
                tpe.getActiveCount(),
                tpe.getPoolSize(),
                queue.size(),
                queue.remainingCapacity(),
                tpe.getCompletedTaskCount(),
                tpe.getCorePoolSize(),
                tpe.getMaximumPoolSize(),
                pendingDeliveries);
    }
}
