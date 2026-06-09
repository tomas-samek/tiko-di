package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioral coverage for #306: {@link EventChainContext#logUnhandledAsyncError(Throwable)}
 * makes an {@code Error} thrown by an async {@code @EventHandler} observable by logging it at
 * ERROR on {@code io.tiko.events}, with the original throwable attached — rather than letting
 * it vanish inside the {@code CompletableFuture}. The generated dispatcher invokes this from its
 * {@code whenComplete} Error branch (asserted structurally in the processor module).
 */
class EventChainContextAsyncErrorLogTest {

    @BeforeEach
    void clearCapturedRecords() {
        CapturingLoggerFinder.clear();
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
