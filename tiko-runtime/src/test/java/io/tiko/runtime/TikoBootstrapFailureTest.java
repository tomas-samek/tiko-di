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
    void cleanupToleratesShutdownFailuresAndStillReportsOriginalFailure() {
        ShutdownThrowingContainer container = new ShutdownThrowingContainer();
        ShutdownThrowingBootstrap started = new ShutdownThrowingBootstrap();
        ThrowingBootstrap bad = new ThrowingBootstrap();
        List<TransportBootstrap> bootstraps = List.of(started, bad);

        // Both the started transport's shutdown and the container's shutdown throw during
        // cleanup; those are swallowed so the ORIGINAL start failure is what surfaces.
        assertThatThrownBy(() -> Tiko.startTransports(container, bootstraps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transport start failed");
    }

    /** #737: the cleanup failures are swallowed, but they travel with the failure that is thrown. */
    @Test
    void cleanupFailuresAreSuppressedOnTheStartFailure() {
        ShutdownThrowingContainer container = new ShutdownThrowingContainer();
        List<TransportBootstrap> bootstraps = List.of(new ShutdownThrowingBootstrap(), new ThrowingBootstrap());

        assertThatThrownBy(() -> Tiko.startTransports(container, bootstraps))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(startFailure -> assertThat(startFailure.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("transport shutdown boom", "container shutdown boom"));
    }

    /** #737: an {@link Error} from a transport's start unwinds like any other start failure. */
    @Test
    void errorFromTransportStartShutsDownContainerAndStartedTransports() {
        BootstrapTestContainer container = new BootstrapTestContainer();
        RecordingBootstrap ok = new RecordingBootstrap();
        TransportBootstrap bad = new RecordingBootstrap() {
            @Override
            public void start(Container started) {
                throw new AssertionError("transport start error");
            }
        };

        assertThatThrownBy(() -> Tiko.startTransports(container, List.of(ok, bad)))
                .isInstanceOf(AssertionError.class)
                .hasMessage("transport start error");

        assertThat(container.shutdowns.get()).isEqualTo(1);
        assertThat(ok.shutdowns.get()).isEqualTo(1);
    }

    /** #737: a transport that fails to stop is logged, and the container still shuts down. */
    @Test
    void transportShutdownFailureOnCloseIsLoggedAndTheContainerStillShutsDown() {
        CapturingLoggerFinder.clear();
        BootstrapTestContainer container = new BootstrapTestContainer();
        RecordingBootstrap ok = new RecordingBootstrap();
        Container handle = Tiko.startTransports(container, List.of(new ShutdownThrowingBootstrap(), ok));

        handle.close();

        assertThat(container.shutdowns.get()).isEqualTo(1);
        assertThat(ok.shutdowns.get()).as("a later transport is still stopped").isEqualTo(1);
        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(entry -> entry.level() == System.Logger.Level.WARNING)
                .filteredOn(entry -> entry.message().contains("ShutdownThrowingBootstrap"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.thrown()).hasMessage("transport shutdown boom"));
    }

    /** #737: an {@link Error} from a transport's shutdown is rethrown only after the container shut down. */
    @Test
    void errorFromTransportShutdownOnCloseIsRethrownAfterTheContainerShutsDown() {
        BootstrapTestContainer container = new BootstrapTestContainer();
        RecordingBootstrap ok = new RecordingBootstrap();
        TransportBootstrap bad = new RecordingBootstrap() {
            @Override
            public void shutdown() {
                throw new AssertionError("transport shutdown error");
            }
        };
        Container handle = Tiko.startTransports(container, List.of(bad, ok));

        assertThatThrownBy(handle::close).isInstanceOf(AssertionError.class).hasMessage("transport shutdown error");

        assertThat(container.shutdowns.get()).isEqualTo(1);
        assertThat(ok.shutdowns.get()).isEqualTo(1);
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
    void missingRegisterHandlersMethodReportsVersionDrift() {
        EventBus bus = new LocalEventBus();
        BootstrapTestContainer_sig container = new BootstrapTestContainer_sig();

        assertThatThrownBy(() -> Tiko.registerEventHandlers(bus, container, BootstrapTestContainer_sig.class))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("no registerHandlers")
                .hasMessageContaining("versions");
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

    private static class RecordingBootstrap implements TransportBootstrap {
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

    /** Starts cleanly but throws when stopped — exercises the swallow in shutdownQuietly(TransportBootstrap). */
    private static final class ShutdownThrowingBootstrap implements TransportBootstrap {
        @Override
        public void start(Container container) {
            // started successfully
        }

        @Override
        public void shutdown() {
            throw new IllegalStateException("transport shutdown boom");
        }
    }

    /** Throws when shut down — exercises the swallow in shutdownQuietly(Container). */
    private static final class ShutdownThrowingContainer extends BootstrapTestContainer {
        @Override
        public void shutdown() {
            super.shutdown();
            throw new IllegalStateException("container shutdown boom");
        }
    }
}
