package io.tiko;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExecutorMetricsTest {

    @Test
    void fromMirrorsThreadPoolExecutorState() {
        try (ThreadPoolExecutor tpe =
                new ThreadPoolExecutor(2, 6, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16))) {
            ExecutorMetrics m = ExecutorMetrics.from(tpe);

            assertThat(m.corePoolSize()).isEqualTo(2);
            assertThat(m.maxPoolSize()).isEqualTo(6);
            assertThat(m.queueSize()).isZero();
            assertThat(m.queueRemainingCapacity()).isEqualTo(16);
            assertThat(m.activeCount()).isZero();
            assertThat(m.completedTaskCount()).isZero();
        }
    }

    /** #761: the snapshot carries the count of pending deliveries the container hands it. */
    @Test
    void fromCarriesTheNumberOfPendingDeliveriesItIsGiven() {
        try (ThreadPoolExecutor tpe =
                new ThreadPoolExecutor(2, 6, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16))) {
            assertThat(ExecutorMetrics.from(tpe, 3).pendingDeliveries()).isEqualTo(3);
            assertThat(ExecutorMetrics.from(tpe).pendingDeliveries())
                    .as("the factory a container generated before 0.8.0 calls")
                    .isZero();
        }
    }

    /** The record's shape before 0.8.0 still constructs, for code compiled against it. */
    @Test
    void constructorWithoutPendingDeliveriesReadsZero() {
        assertThat(new ExecutorMetrics(1, 2, 3, 4, 5L, 6, 7)).isEqualTo(new ExecutorMetrics(1, 2, 3, 4, 5L, 6, 7, 0));
    }

    @Test
    void fromReflectsQueuedAndCompletedWork() throws Exception {
        // Single thread, capacity-1 queue: one task occupies the worker, the rest sit in the queue.
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(8));
        var gate = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        try {
            tpe.execute(() -> {
                started.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            tpe.execute(() -> {}); // queued behind the blocked worker

            ExecutorMetrics busy = ExecutorMetrics.from(tpe);
            assertThat(busy.activeCount()).isEqualTo(1);
            assertThat(busy.queueSize()).isEqualTo(1);

            gate.countDown();
            tpe.shutdown();
            assertThat(tpe.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

            ExecutorMetrics done = ExecutorMetrics.from(tpe);
            assertThat(done.completedTaskCount()).isEqualTo(2);
        } finally {
            tpe.shutdownNow();
        }
    }
}
