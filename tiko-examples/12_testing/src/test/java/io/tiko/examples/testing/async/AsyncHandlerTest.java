package io.tiko.examples.testing.async;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.EventBus;
import io.tiko.examples.testing.events.OrderCreatedEvent;
import io.tiko.test.RecordingEventBus;
import io.tiko.test.TikoTest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates {@link RecordingEventBus#awaitAsyncDispatch} for handlers declared
 * {@code @EventHandler(async = true)}. The fixture handler (see {@link AsyncListener}) is
 * a {@code @Component} in {@code src/test/java}, so the annotation processor wires it into
 * the test container during {@code test-compile}.
 */
@TikoTest
class AsyncHandlerTest {

    @Test
    void awaitAsyncDispatchBlocksUntilHandlersDrain(EventBus bus, RecordingEventBus rec) throws TimeoutException {
        int before = AsyncListener.received.get();
        bus.publish(new OrderCreatedEvent("txn-1", "alice", 1L, Instant.EPOCH));
        rec.awaitAsyncDispatch(Duration.ofSeconds(2));
        assertThat(AsyncListener.received.get()).isGreaterThan(before);
    }
}
