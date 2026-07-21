package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.tiko.ErrorContext;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import io.tiko.EventQueueOverflowException;
import io.tiko.annotations.BackoffStrategy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Runtime behavior of the retry helper backing {@code @EventHandler(async = true, retries = ...)}
 * (#108): a transient failure is retried to success with nothing routed; an exhausted budget routes
 * a single {@link EventHandlerError} carrying the total {@code attempts()}; {@link Error}s are not
 * retried; and a per-attempt timeout (composing #107) counts as a failed attempt.
 */
class EventChainContextRetryTest {

    private static final EventHandlerInfo INFO =
            new EventHandlerInfo(EventChainContextRetryTest.class, "h", String.class, true);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "retry-test-worker");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void transientFailureIsRetriedToSuccessWithNoErrorRouted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        EventChainContext.runAsyncWithRetry(
                () -> {
                    if (calls.incrementAndGet() < 2) {
                        throw new IllegalStateException("first attempt fails");
                    }
                    succeeded.countDown();
                },
                new RetryPolicy(3, 0L, BackoffStrategy.FIXED, 0L),
                executor,
                errors::add,
                INFO,
                "evt");

        assertThat(succeeded.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(errors)
                .as("a handler that recovers on retry routes no error")
                .isEmpty();
    }

    @Test
    void exhaustedRetriesRouteSingleErrorWithAttemptCount() {
        AtomicInteger calls = new AtomicInteger();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        EventChainContext.runAsyncWithRetry(
                () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("always fails");
                },
                new RetryPolicy(2, 0L, BackoffStrategy.FIXED, 0L),
                executor,
                errors::add,
                INFO,
                "evt");

        // retries = 2 → 1 initial + 2 retries = 3 attempts, then one error with attempts = 3.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(errors)
                        .singleElement()
                        .isInstanceOfSatisfying(EventHandlerError.class, e -> {
                            assertThat(e.attempts()).isEqualTo(3);
                            assertThat(e.cause())
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessage("always fails");
                        }));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void errorsAreNotRetriedAndNotRoutedToErrorHandler() {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        EventChainContext.runAsyncWithRetry(
                () -> {
                    calls.incrementAndGet();
                    ran.countDown();
                    throw new StackOverflowError("boom");
                },
                new RetryPolicy(3, 0L, BackoffStrategy.FIXED, 0L),
                executor,
                errors::add,
                INFO,
                "evt");

        await().atMost(Duration.ofSeconds(5)).until(() -> ran.getCount() == 0);
        // Give the loop a window to (wrongly) retry; an Error must stop the loop and stay out of ErrorHandler.
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> true);
        assertThat(calls.get()).as("an Error is not retried").isEqualTo(1);
        assertThat(errors).as("an Error is logged, not routed to ErrorHandler").isEmpty();
    }

    @Test
    void throwPolicyRetryResubmissionOverflowIsLoggedNotSilentlyLost() {
        // #395: under OverflowPolicy.THROW, a retry re-submission that overflows the queue throws
        // EventQueueOverflowException off the publisher's stack (on the backoff scheduler thread).
        // It must degrade to an observable logged drop instead of vanishing. The executor runs the
        // first attempt's body (which fails → schedules a retry) then throws on the re-submission.
        CapturingLoggerFinder.clear();
        AtomicInteger executeCalls = new AtomicInteger();
        var pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                // Call 1 = the initial attempt's body (must run so it fails and triggers a retry);
                // call 2 = the backoff re-submission of the next attempt (overflow under THROW).
                if (executeCalls.incrementAndGet() >= 2) {
                    throw new EventQueueOverflowException("Async event queue is full (THROW).");
                }
                super.execute(command);
            }
        };
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        try {
            EventChainContext.runAsyncWithRetry(
                    () -> {
                        throw new IllegalStateException("attempt fails → schedule retry");
                    },
                    // retries = 1, non-zero backoff so the re-submission defers onto the scheduler thread.
                    new RetryPolicy(1, Duration.ofMillis(20).toNanos(), BackoffStrategy.FIXED, 0L),
                    pool,
                    errors::add,
                    INFO,
                    "evt");

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(CapturingLoggerFinder.RECORDS)
                            .filteredOn(r -> "io.tiko.events".equals(r.loggerName()))
                            .anySatisfy(entry -> {
                                assertThat(entry.level()).isEqualTo(System.Logger.Level.WARNING);
                                assertThat(entry.message()).contains("dropped").contains("THROW");
                            }));
            // THROW degrades to a logged drop off-thread — it is not routed to the ErrorHandler.
            assertThat(errors).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void throwPolicyRetriedAttemptBodySubmitOverflowIsLoggedNotSilentlyLost() {
        // #440: distinct from #395. Here the retry re-submission SUCCEEDS, but the retried attempt's
        // own handler-body submit then overflows the queue under THROW — on a worker thread, off the
        // publisher's stack. That EventQueueOverflowException must degrade to an observable logged
        // drop instead of vanishing. The executor throws only on the 3rd execute: (1) initial body,
        // (2) re-submission of the next attempt, (3) the retried attempt's body submit.
        CapturingLoggerFinder.clear();
        AtomicInteger executeCalls = new AtomicInteger();
        var pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                if (executeCalls.incrementAndGet() >= 3) {
                    throw new EventQueueOverflowException("Async event queue is full (THROW).");
                }
                super.execute(command);
            }
        };
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        try {
            EventChainContext.runAsyncWithRetry(
                    () -> {
                        throw new IllegalStateException("attempt fails → schedule retry");
                    },
                    new RetryPolicy(1, Duration.ofMillis(20).toNanos(), BackoffStrategy.FIXED, 0L),
                    pool,
                    errors::add,
                    INFO,
                    "evt");

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(CapturingLoggerFinder.RECORDS)
                            .filteredOn(r -> "io.tiko.events".equals(r.loggerName()))
                            .anySatisfy(entry -> {
                                assertThat(entry.level()).isEqualTo(System.Logger.Level.WARNING);
                                assertThat(entry.message()).contains("dropped").contains("THROW");
                            }));
            assertThat(errors).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void throwPolicyInitialAttemptSubmitOverflowPropagatesToPublisher() {
        // #440 guard: the synchronous initial attempt (attemptIndex == 0) submits on the publisher's
        // thread, so a THROW overflow there must still propagate to the caller — the retried-attempt
        // log-drop must not swallow the initial-submit contract.
        var pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                throw new EventQueueOverflowException("Async event queue is full (THROW).");
            }
        };
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        try {
            assertThatThrownBy(() -> EventChainContext.runAsyncWithRetry(
                            () -> {},
                            new RetryPolicy(2, Duration.ofMillis(20).toNanos(), BackoffStrategy.FIXED, 0L),
                            pool,
                            errors::add,
                            INFO,
                            "evt"))
                    .isInstanceOf(EventQueueOverflowException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void timedOutAttemptCountsAsFailureAndIsRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);
        CountDownLatch blockFirstAttempt = new CountDownLatch(1);
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        EventChainContext.runAsyncWithRetry(
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        try {
                            blockFirstAttempt.await(); // blocks until the per-attempt timeout interrupts it
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    } else {
                        succeeded.countDown();
                    }
                },
                new RetryPolicy(
                        2, 0L, BackoffStrategy.FIXED, Duration.ofMillis(150).toNanos()),
                executor,
                errors::add,
                INFO,
                "evt");

        assertThat(succeeded.await(5, TimeUnit.SECONDS))
                .as("attempt 1 times out and is retried; attempt 2 succeeds")
                .isTrue();
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(errors).as("recovered on retry after a timed-out attempt").isEmpty();
    }
}
