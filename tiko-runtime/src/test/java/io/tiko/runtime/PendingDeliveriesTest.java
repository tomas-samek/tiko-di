package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.EventQueueOverflowException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #721: a booted container counts the async work handed to its event executor until it has
 * finished, whatever way it ends, so that {@code close()} can wait for it. A count that stays up
 * after a task was dropped would make every later {@code close()} wait out its whole budget.
 */
class PendingDeliveriesTest {

    private final OwnedThreads owner = new OwnedThreads();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch gate = new CountDownLatch(1);
    private ExecutorService executor;
    private ExecutorService supplied;

    @AfterEach
    void stop() {
        gate.countDown();
        if (executor != null) {
            executor.shutdownNow();
        }
        if (supplied != null) {
            supplied.shutdownNow();
        }
        owner.stopRetries();
    }

    /** One worker and room for one queued task, so the third submission overflows. */
    private ExecutorService frameworkExecutor(OverflowPolicy policy) {
        executor = DefaultEventExecutorFactory.create(1, policy, 1, 1, null, owner);
        return executor;
    }

    private ExecutorService suppliedExecutorView() {
        supplied = Executors.newSingleThreadExecutor();
        executor = new RetrySchedulingExecutor(supplied, owner);
        return executor;
    }

    /** A task that says it started and then waits for the test to release it. */
    private Runnable gated() {
        return () -> {
            started.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static long in(Duration budget) {
        return System.nanoTime() + budget.toNanos();
    }

    private int pending() {
        return owner.deliveries().count();
    }

    static Stream<Arguments> executors() {
        return Stream.of(Arguments.of("framework executor", false), Arguments.of("supplied executor", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    void taskCountsFromItsSubmissionUntilItHasRun(String name, boolean suppliedExecutor) throws Exception {
        var pool = suppliedExecutor ? suppliedExecutorView() : frameworkExecutor(OverflowPolicy.CALLER_RUNS);

        pool.execute(gated());
        pool.execute(() -> {});
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(pending()).as("one running, one queued").isEqualTo(2);
        assertThat(owner.awaitDeliveries(in(Duration.ofMillis(100))))
                .as("the wait ends at its deadline while a task still runs")
                .isFalse();

        gate.countDown();

        assertThat(owner.awaitDeliveries(in(Duration.ofSeconds(5)))).isTrue();
        assertThat(pending()).isZero();
    }

    @Test
    void taskDroppedByAFullQueueStopsCounting() throws Exception {
        var pool = frameworkExecutor(OverflowPolicy.DROP);
        pool.execute(gated());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {});

        pool.execute(() -> {}); // no room: dropped with a WARNING

        assertThat(pending()).as("the dropped task is not pending").isEqualTo(2);
    }

    @Test
    void taskRefusedWithAnExceptionStopsCounting() throws Exception {
        var pool = frameworkExecutor(OverflowPolicy.THROW);
        pool.execute(gated());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {});

        assertThatThrownBy(() -> pool.execute(() -> {})).isInstanceOf(EventQueueOverflowException.class);

        assertThat(pending()).isEqualTo(2);
    }

    @Test
    void taskRunOnTheCallerByAFullQueueStopsCounting() throws Exception {
        var pool = frameworkExecutor(OverflowPolicy.CALLER_RUNS);
        pool.execute(gated());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {});
        var ranOnCaller = new AtomicBoolean();

        pool.execute(() -> ranOnCaller.set(true));

        assertThat(ranOnCaller).isTrue();
        assertThat(pending()).isEqualTo(2);
    }

    @Test
    void tasksDiscardedByAForcedShutdownStopCounting() throws Exception {
        var pool = frameworkExecutor(OverflowPolicy.CALLER_RUNS);
        pool.execute(gated());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {});

        assertThat(pool.shutdownNow()).as("the queued task never ran").hasSize(1);

        assertThat(owner.awaitDeliveries(in(Duration.ofSeconds(5))))
                .as("the queued task was released, the running one was interrupted and returned")
                .isTrue();
    }

    @Test
    void taskRejectedByAnExecutorThatIsShuttingDownStopsCounting() {
        var pool = frameworkExecutor(OverflowPolicy.CALLER_RUNS);
        pool.shutdown();

        pool.execute(() -> {}); // dropped with a WARNING

        assertThat(pending()).isZero();
    }

    @Test
    void taskBlockedOnAFullQueueStopsCountingWhenShutdownReleasesIt() throws Exception {
        var pool = frameworkExecutor(OverflowPolicy.BLOCK);
        pool.execute(gated());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {});
        // Closed in reverse: the pool stops first, so a failed assertion cannot leave the publisher blocked.
        try (var publisher = Executors.newSingleThreadExecutor();
                AutoCloseable stopPool = pool::shutdownNow) {
            var blocked = publisher.submit(() -> pool.execute(() -> {})); // waits for queue space
            assertThatThrownBy(() -> blocked.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(pending()).isEqualTo(3);

            pool.shutdownNow();
            blocked.get(5, TimeUnit.SECONDS);

            assertThat(owner.awaitDeliveries(in(Duration.ofSeconds(5)))).isTrue();
        }
    }

    /** A handler that closes the container is itself a pending task; the wait leaves it out. */
    @Test
    void waitFromInsideATaskDoesNotWaitForThatTask() throws Exception {
        var pool = frameworkExecutor(OverflowPolicy.CALLER_RUNS);
        var drained = new AtomicBoolean();
        var done = new CountDownLatch(1);

        pool.execute(() -> {
            drained.set(owner.awaitDeliveries(in(Duration.ofSeconds(5))));
            done.countDown();
        });

        assertThat(done.await(2, TimeUnit.SECONDS))
                .as("the wait did not run to its deadline")
                .isTrue();
        assertThat(drained).isTrue();
    }

    @Test
    void taskASuppliedExecutorRefusesStopsCounting() {
        var view = suppliedExecutorView();
        supplied.shutdown();

        assertThatThrownBy(() -> view.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);

        assertThat(pending()).isZero();
    }

    @Test
    void retryWaitingOutItsBackoffCountsAndIsDroppedWhenTheWaitStarts() {
        var ran = new AtomicBoolean();
        assertThat(owner.scheduleRetry(
                        () -> ran.set(true), Duration.ofMinutes(10).toNanos()))
                .isTrue();
        assertThat(pending()).isEqualTo(1);

        assertThat(owner.awaitDeliveries(in(Duration.ofSeconds(5))))
                .as("close() does not wait for a backoff")
                .isTrue();

        assertThat(ran).isFalse();
        assertThat(owner.scheduleRetry(() -> ran.set(true), 0L))
                .as("no retry is scheduled once the wait has started")
                .isFalse();
        assertThat(pending()).isZero();
    }

    @Test
    void retryCountsUntilItHasResubmitted() throws Exception {
        var resubmitted = new CountDownLatch(1);
        assertThat(owner.scheduleRetry(resubmitted::countDown, 0L)).isTrue();

        assertThat(resubmitted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(owner.deliveries().await(in(Duration.ofSeconds(5)))).isTrue();
    }

    @Test
    void interruptedCallerStillWaitsAndKeepsItsInterrupt() throws Exception {
        var pool = frameworkExecutor(OverflowPolicy.CALLER_RUNS);
        pool.execute(gated());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.currentThread().interrupt();
        try {
            long begin = System.nanoTime();

            boolean drained = owner.awaitDeliveries(in(Duration.ofMillis(200)));

            assertThat(drained).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - begin)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void executorFromOutsideABootIsNotWaitedFor() {
        supplied = Executors.newSingleThreadExecutor();
        supplied.execute(gated());

        assertThat(EventChainContext.awaitPendingDeliveries(supplied, in(Duration.ofSeconds(5))))
                .isTrue();
    }

    @Test
    void closeWaitGoesThroughTheExecutorTheContainerHolds() throws Exception {
        var pool = frameworkExecutor(OverflowPolicy.CALLER_RUNS);
        pool.execute(gated());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(EventChainContext.awaitPendingDeliveries(pool, in(Duration.ofMillis(100))))
                .isFalse();
        gate.countDown();
        assertThat(EventChainContext.awaitPendingDeliveries(pool, in(Duration.ofSeconds(5))))
                .isTrue();
    }
}
