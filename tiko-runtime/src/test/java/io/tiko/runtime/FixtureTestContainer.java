package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.Provider;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Stands in for a generated {@code TestContainerImpl} shipped in a fixtures jar (#497): resolves
 * {@link FakeStubService} by its own class, the way a shadow override addresses a test component,
 * and delegates everything else to a {@link StubContainer} that, like a generated container, consults
 * the shared overrides first.
 */
public final class FixtureTestContainer implements Container {

    private final StubContainer delegate;

    public FixtureTestContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            ExecutorService executor,
            boolean publishLifecycle,
            java.time.Duration shutdownTimeout,
            TikoOptions options) {
        this.delegate = new StubContainer(eventBus, errorHandler, executor, publishLifecycle, shutdownTimeout, options);
    }

    @Override
    public <T> T get(Class<T> type) {
        if (type == FakeStubService.class) return type.cast(FakeStubService.INSTANCE);
        return delegate.get(type);
    }

    @Override
    public <T> T get(Class<T> type, String name) {
        return delegate.get(type, name);
    }

    @Override
    public <T> List<T> getAll(Class<T> type) {
        return delegate.getAll(type);
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type) {
        return delegate.getProvider(type);
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type, String name) {
        return delegate.getProvider(type, name);
    }

    @Override
    public void runInEventScope(Runnable runnable) {
        delegate.runInEventScope(runnable);
    }

    @Override
    public <T> T supplyInEventScope(Supplier<T> supplier) {
        return delegate.supplyInEventScope(supplier);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public EventBus getEventBus() {
        return delegate.getEventBus();
    }

    @Override
    public ExecutorService getEventExecutor() {
        return delegate.getEventExecutor();
    }
}
