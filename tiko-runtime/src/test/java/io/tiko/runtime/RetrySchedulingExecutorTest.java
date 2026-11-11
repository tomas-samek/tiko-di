package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The supplied-executor view a booted container's dispatch code holds (#653) delegates everything to
 * the supplied executor, and the owner's retry thread it schedules on stops cleanly at close.
 */
class RetrySchedulingExecutorTest {

    @BeforeEach
    void clearLog() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void viewDelegatesTasksAndLifecycleToTheSuppliedExecutor() throws Exception {
        var supplied = Executors.newSingleThreadExecutor();
        var view = new RetrySchedulingExecutor(supplied, new OwnedThreads());

        assertThat(view.submit(() -> "ran").get(5, TimeUnit.SECONDS)).isEqualTo("ran");
        assertThat(RetrySchedulingExecutor.unwrap(view)).isSameAs(supplied);
        assertThat(RetrySchedulingExecutor.unwrap(supplied)).isSameAs(supplied);

        view.shutdown();
        assertThat(supplied.isShutdown()).isTrue();
        assertThat(view.isShutdown()).isTrue();
        assertThat(view.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(view.isTerminated()).isTrue();
        assertThat(view.shutdownNow()).isEmpty();
    }

    @Test
    void retriesScheduledThroughTheViewRunOnTheOwnersRetryThread() throws Exception {
        var supplied = Executors.newSingleThreadExecutor();
        var owner = new OwnedThreads();
        var view = new RetrySchedulingExecutor(supplied, owner);
        var ranOn = new AtomicReference<Thread>();
        var ran = new CountDownLatch(1);
        try {
            assertThat(view.scheduleRetry(
                            () -> {
                                ranOn.set(Thread.currentThread());
                                ran.countDown();
                            },
                            0L))
                    .isTrue();
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ranOn.get().getName()).isEqualTo("tiko-event-retry-1");

            owner.stopRetries();

            assertThat(view.scheduleRetry(() -> {}, 0L)).isFalse();
            assertThat(owner.stillAlive(Duration.ofSeconds(5))).isEmpty();
        } finally {
            supplied.shutdownNow();
        }
    }

    @Test
    void retriesStillWaitingAtStopAreDroppedWithOneWarning() {
        var owner = new OwnedThreads();
        assertThat(owner.scheduleRetry(() -> {}, Duration.ofMinutes(10).toNanos()))
                .isTrue();
        assertThat(owner.scheduleRetry(() -> {}, Duration.ofMinutes(10).toNanos()))
                .isTrue();

        owner.stopRetries();

        assertThat(owner.stillAlive(Duration.ofSeconds(5))).isEmpty();
        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> r.message() != null && r.message().contains("pending async retr"))
                .singleElement()
                .satisfies(r -> assertThat(r.message())
                        .isEqualTo("2 pending async retries were dropped: the container is shutting down."));
    }

    @Test
    void stoppingAnOwnerThatNeverRetriedDoesNothing() {
        var owner = new OwnedThreads();

        owner.stopRetries();

        assertThat(owner.scheduleRetry(() -> {}, 0L)).isFalse();
        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> r.message() != null && r.message().contains("pending async retr"))
                .isEmpty();
    }
}
