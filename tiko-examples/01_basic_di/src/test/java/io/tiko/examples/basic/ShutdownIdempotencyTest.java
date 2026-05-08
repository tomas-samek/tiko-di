package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.Tiko;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShutdownIdempotencyTest {

    @BeforeEach
    void resetCounter() {
        ShutdownTestCounter.reset();
    }

    @Test
    void shutdown_called_twice_runs_predestroy_once() {
        Container container = Tiko.create();
        // Touch the singleton so it lands in the singleton map
        container.get(ShutdownTestCounter.class);

        container.shutdown();
        container.shutdown();

        assertThat(ShutdownTestCounter.preDestroyCount.get()).isEqualTo(1);
    }

    @Test
    void try_with_resources_plus_explicit_shutdown_runs_predestroy_once() {
        try (Container container = Tiko.create()) {
            container.get(ShutdownTestCounter.class);
            container.shutdown(); // explicit, then close() at try-end
        }

        assertThat(ShutdownTestCounter.preDestroyCount.get()).isEqualTo(1);
    }
}
