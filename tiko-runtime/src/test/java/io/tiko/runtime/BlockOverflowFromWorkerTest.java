package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * #727: under {@code BLOCK} a full queue makes the publisher wait for space. A pool worker that
 * publishes (an async handler publishing an async-handled event) would wait for space only the
 * workers can free, and with every worker waiting the pool stops for good. Such a task runs on the
 * publishing worker instead.
 */
class BlockOverflowFromWorkerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void workerPublishingIntoItsOwnFullPoolRunsTheTaskItselfAndThePoolKeepsGoing() throws InterruptedException {
        var executor = DefaultEventExecutorFactory.create(1, OverflowPolicy.BLOCK, 1, 1, null);
        int fanOut = 5;
        var nested = new CountDownLatch(fanOut);
        var outer = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                // One worker, queue of one: the second nested submit meets a full queue.
                for (int i = 0; i < fanOut; i++) {
                    executor.execute(nested::countDown);
                }
                outer.countDown();
            });

            assertThat(outer.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the publishing worker is not parked waiting for itself")
                    .isTrue();
            assertThat(nested.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("every nested task ran")
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void outsidePublisherStillWaitsForQueueSpace() throws InterruptedException {
        var executor = DefaultEventExecutorFactory.create(1, OverflowPolicy.BLOCK, 1, 1, null);
        var release = new CountDownLatch(1);
        var ran = new CountDownLatch(3);
        try {
            executor.execute(() -> awaitQuietly(release, ran)); // occupies the worker
            executor.execute(ran::countDown); // fills the queue
            var publisher = Thread.ofPlatform().daemon().start(() -> executor.execute(ran::countDown));

            assertThat(publisher.join(Duration.ofMillis(300)))
                    .as("a publisher from outside the pool gets backpressure")
                    .isFalse();

            release.countDown();
            assertThat(publisher.join(TIMEOUT)).isTrue();
            assertThat(ran.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch release, CountDownLatch ran) {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ran.countDown();
    }
}
