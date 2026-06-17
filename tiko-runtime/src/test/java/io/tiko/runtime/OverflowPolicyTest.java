package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.EventQueueOverflowException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Overflow-policy rejection behavior for the default event executor (#109): THROW throws a typed
 * exception, DROP discards without running, BLOCK makes the caller wait for queue space, and every
 * policy degrades to a logged drop once the executor is shutting down (preserving #346).
 */
class OverflowPolicyTest {

    private ThreadPoolExecutor livePool() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1));
    }

    @Test
    void throwPolicyThrowsTypedExceptionWhileLive() {
        ThreadPoolExecutor pool = livePool();
        try {
            assertThatThrownBy(
                            () -> new OverflowRejectionHandler(OverflowPolicy.THROW).rejectedExecution(() -> {}, pool))
                    .isInstanceOf(EventQueueOverflowException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dropPolicyDiscardsWithoutRunningTheTask() {
        ThreadPoolExecutor pool = livePool();
        try {
            AtomicBoolean ran = new AtomicBoolean(false);
            new OverflowRejectionHandler(OverflowPolicy.DROP).rejectedExecution(() -> ran.set(true), pool);
            assertThat(ran).as("DROP must not run the rejected task").isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void throwPolicyDegradesToDropOnShutdown() {
        ThreadPoolExecutor pool = livePool();
        pool.shutdown(); // now isShutdown() == true
        AtomicBoolean ran = new AtomicBoolean(false);
        // Must NOT throw during shutdown (would break the publisher/shutdown thread) and must not run.
        new OverflowRejectionHandler(OverflowPolicy.THROW).rejectedExecution(() -> ran.set(true), pool);
        assertThat(ran).isFalse();
    }

    @Test
    void blockPolicyMakesTheCallerWaitForQueueSpace() throws Exception {
        // capacity-1 queue, single worker, BLOCK policy.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new OverflowRejectionHandler(OverflowPolicy.BLOCK));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstRunning = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                firstRunning.countDown();
                awaitQuietly(release);
            }); // occupies the worker
            assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();
            pool.execute(() -> {}); // fills the capacity-1 queue

            // The third submit must block (queue full, worker busy) until space frees.
            CountDownLatch submitted = new CountDownLatch(1);
            AtomicBoolean thirdRan = new AtomicBoolean(false);
            Thread publisher = new Thread(() -> {
                pool.execute(() -> thirdRan.set(true));
                submitted.countDown();
            });
            publisher.setDaemon(true);
            publisher.start();

            assertThat(submitted.await(300, TimeUnit.MILLISECONDS))
                    .as("BLOCK must hold the publisher while the queue is full")
                    .isFalse();

            release.countDown(); // worker finishes → queued task runs → space frees → third enqueues
            assertThat(submitted.await(3, TimeUnit.SECONDS))
                    .as("BLOCK unblocks once space is available")
                    .isTrue();
            await(() -> thirdRan.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void factoryBuildsBoundedQueueOfTheRequestedCapacityAndPolicyHandler() {
        ThreadPoolExecutor caller =
                (ThreadPoolExecutor) DefaultEventExecutorFactory.create(7, OverflowPolicy.CALLER_RUNS);
        ThreadPoolExecutor block = (ThreadPoolExecutor) DefaultEventExecutorFactory.create(7, OverflowPolicy.BLOCK);
        try {
            assertThat(caller.getQueue().remainingCapacity()).isEqualTo(7);
            assertThat(caller.getRejectedExecutionHandler()).isInstanceOf(ShutdownAwareCallerRunsPolicy.class);
            assertThat(block.getRejectedExecutionHandler()).isInstanceOf(OverflowRejectionHandler.class);
        } finally {
            caller.shutdownNow();
            block.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
