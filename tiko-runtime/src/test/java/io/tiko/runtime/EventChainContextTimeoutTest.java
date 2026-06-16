package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

        EventChainContext.runAsyncWithTimeout(
                () -> {
                    try {
                        Thread.sleep(10_000);
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

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(errors)
                        .singleElement()
                        .isInstanceOfSatisfying(
                                EventHandlerError.class,
                                e -> assertThat(e.cause()).isInstanceOf(TimeoutException.class)));
        assertThat(interrupted.await(2, TimeUnit.SECONDS))
                .as("the worker thread was interrupted (best-effort)")
                .isTrue();
        // The late RuntimeException from the interrupted body must not add a second error.
        Thread.sleep(150);
        assertThat(errors).hasSize(1);
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
}
