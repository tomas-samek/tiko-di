package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostShutdownGetTest {

    @BeforeEach
    void resetCounter() {
        ShutdownTestCounter.reset();
    }

    @Test
    void get_after_shutdown_throws_illegal_state() {
        Container container = Tiko.create();
        container.shutdown();

        assertThatThrownBy(() -> container.get(ShutdownTestCounter.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Container has been shut down");
    }

    @Test
    void get_with_name_after_shutdown_throws_illegal_state() {
        Container container = Tiko.create();
        container.shutdown();

        assertThatThrownBy(() -> container.get(MessageRepository.class, "any"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Container has been shut down");
    }

    @Test
    void shutdown_returns_immediately_on_repeated_call() {
        Container container = Tiko.create();
        long t0 = System.nanoTime();
        container.shutdown();
        long firstCall = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        container.shutdown();
        long secondCall = System.nanoTime() - t1;

        // Second call should be ~free (just CAS read). Allow generous slack: 1 ms.
        assertThat(secondCall)
                .as("second shutdown() returns via idempotency CAS, not full sequence")
                .isLessThan(1_000_000L);
        assertThat(secondCall).isLessThan(firstCall);
    }
}
