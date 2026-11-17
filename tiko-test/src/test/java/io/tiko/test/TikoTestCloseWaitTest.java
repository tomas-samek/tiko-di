package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.test.fixtures.StuckEvent;
import io.tiko.test.fixtures.StuckHandler;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #721: a container waits for its async handlers when it closes, for at most its
 * {@code shutdownTimeout}. A {@code @TikoTest} container does not, unless the test sets that
 * timeout: the test is over, and a handler still running then is reported as its leak without
 * the test waiting out the default budget first.
 */
class TikoTestCloseWaitTest {

    private static final Duration ENTER_TIMEOUT = Duration.ofSeconds(10);
    private static final String OWNER = "Owner.test";

    @BeforeEach
    void arm() {
        StuckHandler.arm();
    }

    @AfterEach
    void release() throws InterruptedException {
        StuckHandler.releaseAndJoin();
    }

    @Test
    void testContainerReportsAStuckHandlerWithoutWaitingOutTheDefaultShutdownTimeout() throws Exception {
        var booted = BootedContainer.boot(OWNER, options -> {});
        booted.container().getEventBus().publish(new StuckEvent());
        assertThat(StuckHandler.awaitEntered(ENTER_TIMEOUT)).isTrue();
        long begin = System.nanoTime();

        assertThatThrownBy(booted::close).isInstanceOf(AssertionError.class).hasMessageContaining(OWNER);

        assertThat(Duration.ofNanos(System.nanoTime() - begin))
                .as("the default shutdownTimeout is 10 s")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void testThatSetsAShutdownTimeoutGetsTheWait() throws Exception {
        var wait = Duration.ofMillis(500);
        var booted = BootedContainer.boot(OWNER, options -> options.shutdownTimeout(wait));
        booted.container().getEventBus().publish(new StuckEvent());
        assertThat(StuckHandler.awaitEntered(ENTER_TIMEOUT)).isTrue();
        long begin = System.nanoTime();

        assertThatThrownBy(booted::close).isInstanceOf(AssertionError.class);

        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isGreaterThanOrEqualTo(wait);
    }
}
