package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.ErrorHandler;
import io.tiko.EventHandlerInfo;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioral coverage for #306: {@link EventChainContext#logUnhandledAsyncError(Throwable)}
 * makes an {@code Error} thrown by an async {@code @EventHandler} observable by logging it at
 * ERROR on {@code io.tiko.events}, with the original throwable attached — rather than letting
 * it vanish inside the {@code CompletableFuture}. Since #111 the generated dispatcher delegates to
 * {@code EventChainContext.runAsyncWithTimeout} (a plain async handler uses a zero budget), which
 * applies this Error routing in {@link EventChainContext}'s {@code reportAsyncHandlerFailure}.
 */
class EventChainContextAsyncErrorLogTest {

    @BeforeEach
    void clearCapturedRecords() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void errorThroughZeroBudgetDispatchIsLoggedNotRoutedToErrorHandler() {
        // End-to-end on the plain-async path (timeout == 0): an Error must be logged via
        // logUnhandledAsyncError and kept OUT of the ErrorHandler, matching the sync path's
        // Exception-only routing (#306). Guards against a future refactor mis-routing an Error.
        AtomicBoolean errorHandlerCalled = new AtomicBoolean(false);
        ErrorHandler errorHandler = ctx -> errorHandlerCalled.set(true);
        EventHandlerInfo info = new EventHandlerInfo(String.class, "onPing", Object.class, true);
        ExecutorService executor = DefaultEventExecutorFactory.create();
        try {
            EventChainContext.runAsyncWithTimeout(
                    () -> {
                        throw new AssertionError("async boom");
                    },
                    0L,
                    executor,
                    errorHandler,
                    info,
                    "evt");

            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(CapturingLoggerFinder.RECORDS)
                            .filteredOn(r -> "io.tiko.events".equals(r.loggerName()))
                            .anySatisfy(entry -> {
                                assertThat(entry.level()).isEqualTo(System.Logger.Level.ERROR);
                                assertThat(entry.message()).contains("Async @EventHandler threw an Error");
                            }));
            // Once the Error is logged, reportAsyncHandlerFailure has decided — the ErrorHandler is never called.
            assertThat(errorHandlerCalled).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logsErrorAtErrorLevelWithTheThrowableAttached() {
        var boom = new AssertionError("async boom");

        EventChainContext.logUnhandledAsyncError(boom);

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> "io.tiko.events".equals(r.loggerName()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(System.Logger.Level.ERROR);
                    assertThat(entry.thrown()).isSameAs(boom);
                    assertThat(entry.message()).contains("Async @EventHandler threw an Error");
                });
    }
}
