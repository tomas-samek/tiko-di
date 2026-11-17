package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.test.fixtures.RetriedHandler;
import io.tiko.test.fixtures.RetriedPing;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #761: {@code awaitAsyncDispatch} returns only when no attempt of an async handler is running,
 * queued, or waiting out the backoff before its retry.
 */
@TikoTest
class AwaitAsyncDispatchRetryTest {

    @BeforeEach
    void reset() {
        RetriedHandler.reset();
    }

    @Test
    void awaitCoversARetryThatIsWaitingOutItsBackoff(EventBus bus, RecordingEventBus recorder) throws TimeoutException {
        bus.publish(new RetriedPing());

        recorder.awaitAsyncDispatch(Duration.ofSeconds(5));

        assertThat(RetriedHandler.attempts())
                .as("two failed attempts, then the one that succeeds")
                .isEqualTo(3);
    }

    @Test
    void containerReportsWhatIsStillPending(EventBus bus, RecordingEventBus recorder, Container container)
            throws TimeoutException {
        bus.publish(new RetriedPing());

        assertThat(container.pendingAsyncDeliveries())
                .as("the delivery is queued, running or in backoff")
                .isPositive();
        recorder.awaitAsyncDispatch(Duration.ofSeconds(5));

        assertThat(container.pendingAsyncDeliveries()).isZero();
    }
}
