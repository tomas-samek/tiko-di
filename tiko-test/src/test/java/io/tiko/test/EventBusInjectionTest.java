package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.test.fixtures.ImperativePublisher;
import io.tiko.test.fixtures.PingRecorder;
import org.junit.jupiter.api.Test;

/**
 * Behavioral end-to-end for #314: a {@code @Component} that takes the built-in
 * {@code EventBus} as a constructor dependency resolves and can publish imperatively to a
 * handler — no hand-constructed plain class, no {@code container.getEventBus()} plumbing.
 */
@TikoTest
class EventBusInjectionTest {

    @Test
    void componentPublishesViaInjectedEventBus(Container c) {
        ImperativePublisher publisher = c.get(ImperativePublisher.class);
        PingRecorder recorder = c.get(PingRecorder.class);

        publisher.fire("hello");

        assertThat(recorder.received()).containsExactly("hello");
    }
}
