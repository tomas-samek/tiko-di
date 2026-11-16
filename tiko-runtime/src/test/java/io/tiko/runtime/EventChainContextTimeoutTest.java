package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import io.tiko.annotations.BackoffStrategy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Runtime behavior of the timeout helper backing {@code @EventHandler(async = true, timeout = ...)}
 * (#107): a handler that overruns its budget is interrupted and its overrun is routed as an
 * {@link EventHandlerError} whose cause is a {@link TimeoutException}; a handler within budget
 * routes nothing; a handler that fails on its own routes that failure, not a timeout.
 */
class EventChainContextTimeoutTest {

    private static final EventHandlerInfo INFO =
            new EventHandlerInfo(EventChainContextTimeoutTest.class, "h", String.class, true);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timeout-test-worker");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void overrunIsInterruptedAndRoutesTimeoutExceptionExactlyOnce() throws Exception {
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        ErrorHandler handler = errors::add;
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1); // blocks the body until interrupted

        EventChainContext.runAsyncWithTimeout(
                () -> {
                    try {
                        neverReleased.await();
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e); // late failure must NOT double-route
                    }
                },
                Duration.ofMillis(150).toNanos(),
                executor,
                handler,
                INFO,
                "evt");

        // singleElement asserts exactly one outcome — the late RuntimeException from the
        // interrupted body must not add a second error (orTimeout completes the future once).
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(errors)
                        .singleElement()
                        .isInstanceOfSatisfying(
                                EventHandlerError.class,
                                e -> assertThat(e.cause()).isInstanceOf(TimeoutException.class)));
        assertThat(interrupted.await(2, TimeUnit.SECONDS))
                .as("the worker thread was interrupted (best-effort)")
                .isTrue();
    }

    /**
     * #728: the budget is for a handler that runs too long. Time an event spends waiting in the queue
     * is not handler time, so a handler that starts late and finishes within its budget is no timeout.
     */
    @Test
    void timeSpentWaitingInTheQueueDoesNotCountAgainstTheBudget() throws Exception {
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        CountDownLatch workerBusy = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        executor.execute(() -> {
            workerBusy.countDown();
            awaitQuietly(releaseWorker);
        });
        assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();

        EventChainContext.runAsyncWithTimeout(
                ran::countDown, Duration.ofMillis(150).toNanos(), executor, errors::add, INFO, "evt");
        // Longer than the budget, all of it in the queue behind the busy worker.
        await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> true);
        releaseWorker.countDown();

        assertThat(ran.await(5, TimeUnit.SECONDS)).as("the handler ran").isTrue();
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> true);
        assertThat(errors).as("no timeout for a handler that never overran").isEmpty();
    }

    /** #728: an event that never reaches a worker is not a handler that overran. */
    @Test
    void eventStillQueuedWhenTheExecutorStopsIsNotReportedAsATimeout() throws Exception {
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        CountDownLatch workerBusy = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerBusy.countDown();
            awaitQuietly(releaseWorker);
        });
        assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();

        EventChainContext.runAsyncWithTimeout(
                () -> {}, Duration.ofMillis(150).toNanos(), executor, errors::add, INFO, "evt");
        executor.shutdownNow(); // the queued handler is discarded, never run
        releaseWorker.countDown();

        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> true);
        assertThat(errors).isEmpty();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void bodyWithinBudgetRoutesNothing() {
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        CountDownLatch ran = new CountDownLatch(1);

        EventChainContext.runAsyncWithTimeout(
                ran::countDown, Duration.ofSeconds(10).toNanos(), executor, errors::add, INFO, "evt");

        await().atMost(Duration.ofSeconds(5)).until(() -> ran.getCount() == 0);
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> true);
        assertThat(errors).isEmpty();
    }

    @Test
    void bodyThrowingRoutesItsOwnFailureNotTimeout() {
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        EventChainContext.runAsyncWithTimeout(
                () -> {
                    throw new IllegalStateException("boom");
                },
                Duration.ofSeconds(10).toNanos(),
                executor,
                errors::add,
                INFO,
                "evt");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(errors)
                        .singleElement()
                        .isInstanceOfSatisfying(
                                EventHandlerError.class,
                                e -> assertThat(e.cause())
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessage("boom")));
    }

    /** An executor that runs every task on the submitting thread, as CALLER_RUNS does when the queue is full. */
    private static final class InlineExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            // nothing to shut down
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /**
     * A handler the executor runs inline, on the publisher's thread, is not time-boxed: it runs to
     * completion, as the {@code timeout} Javadoc says. Reporting it afterwards as timed out would
     * interrupt the publisher and, with retries, run a completed handler again.
     */
    @Test
    void inlineRunOverItsBudgetIsNeitherReportedNorInterrupted() {
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        var ran = new AtomicInteger();

        try (var executor = new InlineExecutor()) {
            EventChainContext.runAsyncWithRetry(
                    () -> {
                        ran.incrementAndGet();
                        await().pollDelay(Duration.ofMillis(200))
                                .atMost(Duration.ofSeconds(5))
                                .until(() -> true);
                    },
                    new RetryPolicy(
                            2, 0L, BackoffStrategy.FIXED, Duration.ofMillis(50).toNanos()),
                    executor,
                    errors::add,
                    INFO,
                    "evt");

            assertThat(Thread.currentThread().isInterrupted())
                    .as("the publisher's thread is not interrupted")
                    .isFalse();
            // The timer of a breached budget fires on another thread: give a late report time to arrive.
            await().pollDelay(Duration.ofMillis(300))
                    .atMost(Duration.ofSeconds(5))
                    .until(() -> true);
            assertThat(errors).as("no timeout is routed").isEmpty();
            assertThat(ran).as("the completed handler is not run again").hasValue(1);
        } finally {
            // Clears a stray interrupt so it cannot reach the next test.
            Thread.interrupted();
        }
    }
}
