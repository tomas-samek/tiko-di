package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CountingThreadPoolExecutorTest {

    private static CountingThreadPoolExecutor singleWorker() {
        return new CountingThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Test
    void isAThreadPoolExecutorSoTheExistingDrainPathStillApplies() {
        try (var cte = singleWorker()) {
            assertThat(cte).isInstanceOf(ThreadPoolExecutor.class);
        }
    }

    @Test
    void countsSubmittedTaskBeforeAWorkerPicksItUp() throws InterruptedException {
        var cte = singleWorker();
        var release = new CountDownLatch(1);
        var occupied = new CountDownLatch(1);
        try {
            // Fill the only worker so the next submit sits in the queue, untouched.
            cte.execute(() -> {
                occupied.countDown();
                await(release);
            });
            occupied.await();

            // This task is merely queued — no worker has locked it, so getActiveCount() is 1,
            // yet in-flight must already count it: submit-time accounting, not execution-time.
            cte.execute(() -> {});
            assertThat(cte.inFlight()).isEqualTo(2L);

            release.countDown();
            cte.shutdown();
            assertThat(cte.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cte.inFlight()).isZero();
        } finally {
            cte.shutdownNow();
        }
    }

    @Test
    void doesNotLeakInFlightWhenExecuteIsRejectedWithAnException() {
        // AbortPolicy throws on rejection (the framework's THROW overflow shape). After shutdown the
        // next submit is rejected, and the increment must be undone rather than leak.
        var cte = new CountingThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        cte.shutdownNow();
        assertThatThrownBy(() -> cte.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
        assertThat(cte.inFlight()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
