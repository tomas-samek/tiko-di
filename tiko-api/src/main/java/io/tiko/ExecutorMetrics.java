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
 */
public record ExecutorMetrics(
        int activeCount,
        int poolSize,
        int queueSize,
        int queueRemainingCapacity,
        long completedTaskCount,
        int corePoolSize,
        int maxPoolSize) {

    /**
     * Samples the given executor into an immutable snapshot.
     *
     * @param tpe the live framework executor to sample
     * @return a snapshot of {@code tpe}'s current state
     */
    public static ExecutorMetrics from(ThreadPoolExecutor tpe) {
        var queue = tpe.getQueue();
        return new ExecutorMetrics(
                tpe.getActiveCount(),
                tpe.getPoolSize(),
                queue.size(),
                queue.remainingCapacity(),
                tpe.getCompletedTaskCount(),
                tpe.getCorePoolSize(),
                tpe.getMaximumPoolSize());
    }
}
