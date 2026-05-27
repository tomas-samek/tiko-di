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
 * Unit tests for the JVM shutdown-hook wrapper (#92). Exercised directly with a stub delegate
 * (tiko-runtime has no annotation processor, so there is no generated container here). Each test
 * removes the hook it registered so the test JVM does not accumulate hooks.
 */
class ShutdownHookContainerTest {

    @Test
    void optionRegistersHookByDefaultAndCanBeDisabled() {
        assertThat(TikoOptions.builder().build().registerShutdownHook())
                .as("default registers a JVM shutdown hook")
                .isTrue();
        assertThat(TikoOptions.builder().registerShutdownHook(false).build().registerShutdownHook())
                .isFalse();
    }

    @Test
    void registersHookOnConstruction() {
        Tiko.ShutdownHookContainer c = new Tiko.ShutdownHookContainer(new RecordingContainer());
        // removeShutdownHook returns true only if the hook was actually registered.
        assertThat(Runtime.getRuntime().removeShutdownHook(c.shutdownHook())).isTrue();
    }

    @Test
    void explicitShutdownRemovesHookAndDelegates() {
        RecordingContainer delegate = new RecordingContainer();
        Tiko.ShutdownHookContainer c = new Tiko.ShutdownHookContainer(delegate);
        Thread hook = c.shutdownHook();

        c.shutdown();

        assertThat(delegate.shutdownCount).isEqualTo(1);
        // Already removed by shutdown() → removeShutdownHook now returns false (not registered).
        assertThat(Runtime.getRuntime().removeShutdownHook(hook)).isFalse();
    }

    @Test
    void hookInvokesDelegateShutdown() {
        RecordingContainer delegate = new RecordingContainer();
        Tiko.ShutdownHookContainer c = new Tiko.ShutdownHookContainer(delegate);
        Thread hook = c.shutdownHook();

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
