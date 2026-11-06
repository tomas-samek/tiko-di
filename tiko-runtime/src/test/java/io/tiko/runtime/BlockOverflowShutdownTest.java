package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockOverflowShutdownTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @BeforeEach
    void clearLogs() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void shutdownReleasesPublisherWhileQueueIsStillFull() throws Exception {
        var pool = (ThreadPoolExecutor) DefaultEventExecutorFactory.create(1, OverflowPolicy.BLOCK, 1, 1, null);
        var releaseWorker = new CountDownLatch(1);
        var queuedRan = new AtomicBoolean();
        var overflowRan = new AtomicBoolean();
        var publisher = Thread.ofPlatform().daemon().unstarted(() -> pool.execute(() -> overflowRan.set(true)));
        try {
            pool.execute(() -> awaitQuietly(releaseWorker));
            pool.execute(() -> queuedRan.set(true));
            publisher.start();
            awaitPublisherInBlockingSubmission(publisher);

            pool.shutdown();

            assertThat(publisher.join(TIMEOUT)).isTrue();
            assertThat(pool.getQueue()).hasSize(1);
            assertShutdownDropLogged();

            releaseWorker.countDown();
            assertThat(pool.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isTrue();
            assertThat(queuedRan).isTrue();
            assertThat(overflowRan).isFalse();
        } finally {
            releaseWorker.countDown();
            publisher.interrupt();
            pool.shutdownNow();
            publisher.join(TIMEOUT);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forcedShutdownDuringQueueOfferDoesNotStrandTask(boolean pauseAfterOffer) throws Exception {
        var queue = new PausingQueue(pauseAfterOffer);
        var pool = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, queue, new OverflowRejectionHandler(OverflowPolicy.BLOCK));
        var releaseWorker = new CountDownLatch(1);
        var overflowRan = new AtomicBoolean();
        var publisher = Thread.ofPlatform().daemon().unstarted(() -> pool.execute(() -> overflowRan.set(true)));
        try {
            pool.execute(() -> awaitQuietly(releaseWorker));
            Runnable queued = () -> {};
            pool.execute(queued);
            publisher.start();
            if (pauseAfterOffer) {
                // Make space for the blocked publisher while the worker remains occupied.
                assertThat(queue.blockingEnqueueEntered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                        .isTrue();
                assertThat(pool.remove(queued)).isTrue();
            }
            assertThat(queue.paused.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isTrue();

            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isTrue();
            queue.resume.countDown();

            assertThat(publisher.join(TIMEOUT)).isTrue();
            assertThat(pool.getQueue()).isEmpty();
            assertThat(overflowRan).isFalse();
            assertShutdownDropLogged();
        } finally {
            releaseWorker.countDown();
            queue.resume.countDown();
            publisher.interrupt();
            pool.shutdownNow();
            publisher.join(TIMEOUT);
        }
    }

    @Test
    void livePoolWithNoCoreWorkersStartsAWorkerForTheLateSubmission() throws Exception {
        var queue = new PausingQueue(false);
        var pool = new ThreadPoolExecutor(
                0, 1, 0, TimeUnit.SECONDS, queue, new OverflowRejectionHandler(OverflowPolicy.BLOCK));
        var firstRunning = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var overflowRan = new CountDownLatch(1);
        var publisher = Thread.ofPlatform().daemon().unstarted(() -> pool.execute(overflowRan::countDown));
        try {
            pool.execute(() -> {
                firstRunning.countDown();
                awaitQuietly(releaseWorker);
            });
            assertThat(firstRunning.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isTrue();
            pool.execute(() -> {});
            publisher.start();
            assertThat(queue.paused.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isTrue();

            releaseWorker.countDown();
            await().atMost(TIMEOUT).until(() -> pool.getPoolSize() == 0);
            queue.resume.countDown();

            assertThat(publisher.join(TIMEOUT)).isTrue();
            assertThat(overflowRan.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isTrue();
        } finally {
            releaseWorker.countDown();
            queue.resume.countDown();
            publisher.interrupt();
            pool.shutdownNow();
            publisher.join(TIMEOUT);
        }
    }

    @Test
    void interruptReleasesPublisherAndPreservesInterruptFlag() throws Exception {
        var pool = (ThreadPoolExecutor) DefaultEventExecutorFactory.create(1, OverflowPolicy.BLOCK, 1, 1, null);
        var releaseWorker = new CountDownLatch(1);
        var overflowRan = new AtomicBoolean();
        var interrupted = new AtomicBoolean();
        var publisher = Thread.ofPlatform().daemon().unstarted(() -> {
            pool.execute(() -> overflowRan.set(true));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        try {
            pool.execute(() -> awaitQuietly(releaseWorker));
            pool.execute(() -> {});
            publisher.start();
            awaitPublisherInBlockingSubmission(publisher);

            publisher.interrupt();

            assertThat(publisher.join(TIMEOUT)).isTrue();
            assertThat(interrupted).isTrue();
            releaseWorker.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .isTrue();
            assertThat(overflowRan).isFalse();
        } finally {
            releaseWorker.countDown();
            publisher.interrupt();
            pool.shutdownNow();
            publisher.join(TIMEOUT);
        }
    }

    private static void awaitPublisherInBlockingSubmission(Thread publisher) {
        await().atMost(TIMEOUT)
                .until(() -> Arrays.stream(publisher.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(OverflowRejectionHandler.class.getName())
                                && frame.getMethodName().equals("enqueueBlocking")));
    }

    private static void assertShutdownDropLogged() {
        assertThat(CapturingLoggerFinder.RECORDS)
                .anyMatch(entry -> entry.level() == System.Logger.Level.WARNING
                        && entry.loggerName().equals("io.tiko.events")
                        && entry.message().contains("executor is shutting down"));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PausingQueue extends LinkedBlockingQueue<Runnable> {
        private final boolean pauseAfterOffer;
        private final CountDownLatch blockingEnqueueEntered = new CountDownLatch(1);
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        PausingQueue(boolean pauseAfterOffer) {
            super(1);
            this.pauseAfterOffer = pauseAfterOffer;
        }

        @Override
        public void put(Runnable task) throws InterruptedException {
            beforeOffer();
            super.put(task);
            afterOffer();
        }

        @Override
        public boolean offer(Runnable task, long timeout, TimeUnit unit) throws InterruptedException {
            beforeOffer();
            boolean offered = super.offer(task, timeout, unit);
            if (offered) {
                afterOffer();
            }
            return offered;
        }

        private void beforeOffer() throws InterruptedException {
            blockingEnqueueEntered.countDown();
            if (!pauseAfterOffer) {
                pause();
            }
        }

        private void afterOffer() throws InterruptedException {
            if (pauseAfterOffer) {
                pause();
            }
        }

        private void pause() throws InterruptedException {
            paused.countDown();
            resume.await();
        }
    }
}
