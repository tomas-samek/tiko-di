package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.Provider;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Minimal {@link Container} for bootstrap-failure tests (#348): records how many times
 * {@code shutdown()} was called so a test can assert a failed bootstrap left no
 * started-but-unreachable container behind. Non-final so the {@code _reg}-suffixed
 * subclass can drive the registry-name lookup in {@code registerEventHandlers}.
 */
public class BootstrapTestContainer implements Container {

    public final AtomicInteger shutdowns = new AtomicInteger();

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
    public void runInEventScope(Runnable runnable) {
        runnable.run();
    }

    @Override
    public <T> T supplyInEventScope(Supplier<T> supplier) {
        return supplier.get();
    }

    @Override
    public void start() {
        // no-op: test stub
    }

    @Override
    public void shutdown() {
        shutdowns.incrementAndGet();
    }

    @Override
    public EventBus getEventBus() {
        return new LocalEventBus();
    }

    @Override
    public ExecutorService getEventExecutor() {
        return Executors.newSingleThreadExecutor();
    }
}
