package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.Provider;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TikoDaemon} (#92). Exercised directly with a stub delegate (tiko-runtime
 * has no annotation processor, so there is no generated container here). Each test removes the
 * hook it registered so the test JVM does not accumulate hooks.
 */
class TikoDaemonTest {

    @Test
    void installsHookOnConstruction() {
        TikoDaemon daemon = new TikoDaemon(new RecordingContainer());
        // removeShutdownHook returns true only if the hook was actually registered.
        assertThat(Runtime.getRuntime().removeShutdownHook(daemon.shutdownHook()))
                .isTrue();
    }

    @Test
    void containerAccessorReturnsUnderlying() {
        RecordingContainer delegate = new RecordingContainer();
        TikoDaemon daemon = new TikoDaemon(delegate);
        try {
            assertThat(daemon.container()).isSameAs(delegate);
        } finally {
            Runtime.getRuntime().removeShutdownHook(daemon.shutdownHook());
        }
    }

    @Test
    void stopRemovesHookAndShutsDown() {
        RecordingContainer delegate = new RecordingContainer();
        TikoDaemon daemon = new TikoDaemon(delegate);
        Thread hook = daemon.shutdownHook();

        daemon.stop();

        assertThat(delegate.shutdownCount).isEqualTo(1);
        // Already removed by stop() → removeShutdownHook now returns false (not registered).
        assertThat(Runtime.getRuntime().removeShutdownHook(hook)).isFalse();
    }

    @Test
    void hookInvokesContainerShutdown() {
        RecordingContainer delegate = new RecordingContainer();
        TikoDaemon daemon = new TikoDaemon(delegate);
        Thread hook = daemon.shutdownHook();

        hook.run(); // run the hook's target synchronously

        assertThat(delegate.shutdownCount).isEqualTo(1);
        Runtime.getRuntime().removeShutdownHook(hook); // cleanup
    }

    /** Minimal {@link Container} that only records {@code shutdown()} calls. */
    private static final class RecordingContainer implements Container {
        int shutdownCount;

        @Override
        public void shutdown() {
            shutdownCount++;
        }

        @Override
        public <T> T get(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T get(Class<T> type, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<T> getAll(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Provider<T> getProvider(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Provider<T> getProvider(Class<T> type, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runInRequestScope(Runnable runnable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T supplyInRequestScope(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runInEventScope(Runnable runnable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T supplyInEventScope(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventBus getEventBus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutorService getEventExecutor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ErrorHandler getErrorHandler() {
            throw new UnsupportedOperationException();
        }
    }
}
