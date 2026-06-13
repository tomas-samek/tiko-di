package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.EventBus;
import io.tiko.TransportBootstrap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap failure handling in {@code Tiko.create()} (#348): a transport that fails to
 * start must not leak the already-started container, and a registry whose
 * {@code registerHandlers} fails must surface loudly instead of being swallowed.
 */
class TikoBootstrapFailureTest {

    // --- transport-start failure: no leaked started container --------------------------

    @Test
    void transportStartFailureShutsDownContainerAndStartedTransports() {
        BootstrapTestContainer container = new BootstrapTestContainer();
        RecordingBootstrap ok = new RecordingBootstrap();
        ThrowingBootstrap bad = new ThrowingBootstrap();
        List<TransportBootstrap> bootstraps = List.of(ok, bad);

        assertThatThrownBy(() -> Tiko.startTransports(container, bootstraps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transport start failed");

        assertThat(container.shutdowns.get())
                .as("the already-started container must be torn down, not leaked")
                .isEqualTo(1);
        assertThat(ok.shutdowns.get())
                .as("a transport that did start must be stopped during cleanup")
                .isEqualTo(1);
        assertThat(bad.shutdowns.get())
                .as("a transport that never started is not stopped")
                .isZero();
    }

    @Test
    void noTransportsReturnsBareContainerWithoutShutdown() {
        BootstrapTestContainer container = new BootstrapTestContainer();

        Container result = Tiko.startTransports(container, List.of());

        assertThat(result).isSameAs(container);
        assertThat(container.shutdowns.get()).isZero();
    }

    // --- registration failure: loud, not swallowed -------------------------------------

    @Test
    void throwingRegisterHandlersFailsLoudlyNamingTheRegistry() {
        EventBus bus = new LocalEventBus();
        BootstrapTestContainer_reg container = new BootstrapTestContainer_reg();

        assertThatThrownBy(() -> Tiko.registerEventHandlers(bus, container, BootstrapTestContainer_reg.class))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("io.tiko.generated.EventRegistry_reg")
                .hasMessageContaining("registration boom");
    }

    @Test
    void missingRegistryClassRemainsTheOptionalNoOpPath() {
        EventBus bus = new LocalEventBus();
        StubContainer container = new StubContainer(bus, ctx -> {}, null, true, null, null);

        // StubContainer's simple name has no '_' suffix, so the lookup targets
        // io.tiko.generated.EventRegistry, which does not exist — the legitimately-optional case.
        assertThatCode(() -> Tiko.registerEventHandlers(bus, container, StubContainer.class))
                .doesNotThrowAnyException();
    }

    private static final class RecordingBootstrap implements TransportBootstrap {
        final AtomicInteger shutdowns = new AtomicInteger();

        @Override
        public void start(Container container) {
            // started successfully
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
        }
    }

    private static final class ThrowingBootstrap implements TransportBootstrap {
        final AtomicInteger shutdowns = new AtomicInteger();

        @Override
        public void start(Container container) {
            throw new IllegalStateException("transport start failed");
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
        }
    }
}
