package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreDestroyBypassTest {

    @BeforeEach
    void resetCounter() {
        ShutdownTestCounter.reset();
    }

    @Test
    void predestroy_can_call_container_get_via_thread_local_bypass() {
        Container container = Tiko.create();
        ShutdownTestCounter.CONTAINER_REF.set(container);
        // Touch so the bean is in the singleton map
        container.get(ShutdownTestCounter.class);

        container.shutdown();

        assertThat(ShutdownTestCounter.ERROR_DURING_PREDESTROY.get())
                .as("@PreDestroy calling container.get() must not trip the post-shutdown gate")
                .isNull();
        assertThat(ShutdownTestCounter.RETRIEVED_DURING_PREDESTROY.get()).isInstanceOf(ShutdownTestCounter.class);
    }

    /**
     * #710: with a {@code teardownTimeout} the hook runs on a teardown thread, not on the thread
     * that called {@code shutdown()}; it can look components up there all the same.
     */
    @Test
    void preDestroyCanCallContainerGetUnderATeardownTimeout() {
        Container container = Tiko.create(
                TikoOptions.builder().teardownTimeout(Duration.ofSeconds(5)).build());
        ShutdownTestCounter.CONTAINER_REF.set(container);
        container.get(ShutdownTestCounter.class);

        container.shutdown();

        assertThat(ShutdownTestCounter.ERROR_DURING_PREDESTROY.get())
                .as("@PreDestroy calling container.get() on the teardown thread")
                .isNull();
        assertThat(ShutdownTestCounter.RETRIEVED_DURING_PREDESTROY.get()).isInstanceOf(ShutdownTestCounter.class);
    }
}
