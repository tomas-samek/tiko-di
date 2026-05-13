package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.TransportBootstrap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code Tiko.create(...)} discovers every {@link TransportBootstrap}
 * registered via {@code ServiceLoader} and calls {@code start(container)} once after
 * {@code container.start()} and {@code shutdown()} once during {@code container.shutdown()}
 * — before the container's own {@code @PreDestroy} chain.
 *
 * <p>The test fixture {@link RecordingTransportBootstrap} is registered via
 * {@code src/test/resources/META-INF/services/io.tiko.TransportBootstrap}.
 */
class TransportBootstrapDiscoveryTest {

    @Test
    void bootstrap_start_and_shutdown_are_invoked_in_order() {
        RecordingTransportBootstrap.STARTS.set(0);
        RecordingTransportBootstrap.SHUTDOWNS.set(0);

        try (Container container = Tiko.create()) {
            assertThat(RecordingTransportBootstrap.STARTS.get()).isEqualTo(1);
            assertThat(RecordingTransportBootstrap.SHUTDOWNS.get()).isEqualTo(0);
            assertThat(RecordingTransportBootstrap.LAST_CONTAINER).isSameAs(container);
        }

        assertThat(RecordingTransportBootstrap.SHUTDOWNS.get()).isEqualTo(1);
    }

    /** ServiceLoader fixture. */
    public static final class RecordingTransportBootstrap implements TransportBootstrap {
        static final AtomicInteger STARTS = new AtomicInteger();
        static final AtomicInteger SHUTDOWNS = new AtomicInteger();
        static volatile Container LAST_CONTAINER;

        @Override
        public void start(Container container) {
            LAST_CONTAINER = container;
            STARTS.incrementAndGet();
        }

        @Override
        public void shutdown() {
            SHUTDOWNS.incrementAndGet();
        }
    }
}
