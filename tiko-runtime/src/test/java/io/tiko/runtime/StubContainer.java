package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.Provider;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Minimal container used only by unit tests in tiko-runtime (no annotation processor
 * runs here, so there is no generated TikoContainerImpl). Resolves exactly one type —
 * the {@link StubService} interface, mimicking a generated container's routable-types
 * dispatch and override consultation — and rejects everything else, so lookup-miss
 * tests stay misses.
 *
 * <p>Registered via {@code src/test/resources/META-INF/tiko/container.properties};
 * its {@code components.txt} is intentionally empty.
 */
public final class StubContainer implements Container {

    /** The instance returned for interface-keyed {@link StubService} lookups (#335). */
    static final StubService STUB_SERVICE = new StubService() {};

    // Stored so getEventBus() returns whatever Tiko.createInternal handed in (decorated or not).
    // EventBusDecoratorTest depends on this round-trip: the test wraps the raw LocalEventBus
    // via TikoOptions.eventBusDecorator and asserts the container exposes the wrapper.
    private final EventBus eventBus;

    // Consulted before own resolution, mirroring the generated get(Class) head.
    private final TikoOptions options;

    // Mirrors the generated container: it builds and owns the framework default executor when
    // none is passed in (single-module boot) and drains it on shutdown (#634).
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final java.time.Duration shutdownTimeout;

    /**
     * The executor the last constructed stub dispatches through: what generated {@code EventRegistry}
     * code passes to {@code EventChainContext} as {@code container.getEventExecutor()} (#653).
     */
    static volatile ExecutorService lastDispatchExecutor;

    // Tiko.createSingleModuleContainer + AggregatingContainer.processContainerResource
    // both reflectively look up the canonical 6-arg constructor (TikoOptions is the
    // sixth param so generated getters can consult test/runtime overrides via this field).
    public StubContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            ExecutorService executor,
            boolean publishLifecycle,
            java.time.Duration shutdownTimeout,
            TikoOptions options) {
        this.eventBus = eventBus;
        this.options = options;
        this.ownsExecutor = executor == null;
        if (executor != null) {
            this.executor = executor;
        } else if (options != null) {
            this.executor = DefaultEventExecutorFactory.create(
                    options.queueCapacity(),
                    options.onOverflow(),
                    options.eventExecutorCoreSize(),
                    options.eventExecutorMaxSize(),
                    options.eventExecutorKeepAlive());
        } else {
            this.executor = DefaultEventExecutorFactory.create();
        }
        this.shutdownTimeout = shutdownTimeout != null ? shutdownTimeout : java.time.Duration.ofSeconds(10);
        lastDispatchExecutor = this.executor;
    }

    @Override
    public <T> T get(Class<T> type) {
        var override = options == null ? null : options.getOverride(type);
        if (override != null) {
            return type.cast(override.get());
        }
        if (type == StubService.class) {
            return type.cast(STUB_SERVICE);
        }
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
        if (type == StubService.class || (options != null && options.getOverride(type) != null)) {
            return () -> get(type);
        }
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
        if (!ownsExecutor) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public ExecutorService getEventExecutor() {
        return executor;
    }

    /** Config types injected through the aggregator, recorded for {@code AggregatingContainerResourceCharsetTest}. */
    static final List<Class<?>> INJECTED_CONFIG_TYPES = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void injectConfigs(java.util.Map<Class<?>, Object> configs) {
        INJECTED_CONFIG_TYPES.addAll(configs.keySet());
    }
}
