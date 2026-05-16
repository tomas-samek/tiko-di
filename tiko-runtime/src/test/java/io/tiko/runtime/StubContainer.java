package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.Provider;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Minimal no-op container used only by unit tests in tiko-runtime (no annotation processor
 * runs here, so there is no generated TikoContainerImpl).
 *
 * <p>Registered via {@code src/test/resources/META-INF/tiko/container.properties}.
 */
public final class StubContainer implements Container {

    // Tiko.createSingleModuleContainer reflectively calls this constructor.
    public StubContainer(
            EventBus eventBus, ErrorHandler errorHandler, ExecutorService executor, boolean publishLifecycle) {}

    // Tiko.createSingleModuleContainer + AggregatingContainer.processContainerResource
    // both reflectively look up the 5-arg constructor after #48.
    public StubContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            ExecutorService executor,
            boolean publishLifecycle,
            java.time.Duration shutdownTimeout) {}

    @Override
    public <T> T get(Class<T> type) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public <T> T get(Class<T> type, String name) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public <T> List<T> getAll(Class<T> type) {
        return List.of();
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type, String name) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void runInRequestScope(Runnable runnable) {
        runnable.run();
    }

    @Override
    public <T> T supplyInRequestScope(Supplier<T> supplier) {
        return supplier.get();
    }

    @Override
    public void runInEventScope(Runnable runnable) {
        runnable.run();
    }

    @Override
    public <T> T supplyInEventScope(Supplier<T> supplier) {
        return supplier.get();
    }

    @Override
    public void start() {}

    @Override
    public void shutdown() {}

    @Override
    public EventBus getEventBus() {
        return new LocalEventBus();
    }

    @Override
    public ExecutorService getEventExecutor() {
        return Executors.newSingleThreadExecutor();
    }
}
