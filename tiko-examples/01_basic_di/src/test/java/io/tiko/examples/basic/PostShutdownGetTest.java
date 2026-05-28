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
    void repeatedShutdownRunsPreDestroyExactlyOnce() {
        // #85: idempotency is proven by a side-effect counter, not a wall-clock bound. The old test
        // asserted an absolute 1 ms cap on the second call, which flaked under JIT/GC/CI noise at
        // sub-millisecond granularity. Counting @PreDestroy invocations is deterministic.
        Container container = Tiko.create();
        // Force the SINGLETON to exist so its @PreDestroy participates in shutdown.
        container.get(ShutdownTestCounter.class);

        container.shutdown();
        assertThat(ShutdownTestCounter.preDestroyCount.get())
                .as("first shutdown() runs @PreDestroy")
                .isEqualTo(1);

        // The idempotency CAS short-circuits the second call, so the teardown sequence — and thus
        // @PreDestroy — must not run again.
        container.shutdown();
        assertThat(ShutdownTestCounter.preDestroyCount.get())
                .as("second shutdown() is a no-op — @PreDestroy is not re-run")
                .isEqualTo(1);
    }
}
