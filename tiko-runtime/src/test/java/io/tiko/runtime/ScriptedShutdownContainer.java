package io.tiko.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.PreDestroyFailure;
import io.tiko.Provider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Test module whose {@code shutdown()} behaves the way a test scripts it, mirroring the generated
 * container's teardown (#634): a {@code @PreDestroy} hook run through {@link BoundedExecution} under
 * {@code teardownTimeout}, an observer failure rethrown as the generated code does (directly when
 * single-module, wrapped for the aggregate otherwise), or a plain shutdown failure.
 *
 * <p>Booted reflectively through a temporary descriptor, so the script lives in static fields; tests
 * set it before booting and call {@link #reset()} afterwards.
 */
public final class ScriptedShutdownContainer implements Container {

    /** When set, the teardown hook waits for this latch, ignoring interrupts. */
    static volatile CountDownLatch stuckTeardownUntil;

    /** When set, shutdown ends by rethrowing it as a {@code @PreDestroy} observer failure. */
    static volatile Throwable observerFailure;

    /** When set, shutdown ends by throwing it. */
    static volatile RuntimeException shutdownFailure;

    static void reset() {
        stuckTeardownUntil = null;
        observerFailure = null;
        shutdownFailure = null;
    }

    private final EventBus eventBus;
    private final ErrorHandler errorHandler;
    private final boolean publishLifecycle;
    private final Duration teardownTimeout;
    private final ExecutorService executor;

    // S1172: the runtime looks this constructor up by its canonical signature; not every parameter is used.
    @SuppressWarnings("java:S1172")
    public ScriptedShutdownContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            ExecutorService executor,
            boolean publishLifecycle,
            Duration shutdownTimeout,
            TikoOptions options) {
        this.eventBus = eventBus;
        this.errorHandler = errorHandler;
        this.publishLifecycle = publishLifecycle;
        this.teardownTimeout = options.teardownTimeout();
        this.executor = executor != null ? executor : DefaultEventExecutorFactory.create();
    }

    @Override
    public void shutdown() {
        var stuck = stuckTeardownUntil;
        if (stuck != null) {
            BoundedExecution.run(
                    () -> awaitIgnoringInterrupts(stuck),
                    teardownTimeout,
                    errorHandler,
                    cause -> new PreDestroyFailure(ScriptedShutdownContainer.class, cause));
        }
        if (publishLifecycle) {
            executor.shutdownNow();
        }
        var observed = observerFailure;
        if (observed != null) {
            if (publishLifecycle) {
                throw Unchecked.<RuntimeException>sneakyThrow(observed);
            }
            throw new BoundedExecution.ModuleTeardownFailure(observed);
        }
        var failure = shutdownFailure;
        if (failure != null) {
            throw failure;
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        var interrupted = false;
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

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
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public ExecutorService getEventExecutor() {
        return executor;
    }

    /** Single-module boots inject the test classpath's bound configs here; this module ignores them. */
    @SuppressWarnings("java:S1172") // called reflectively with the bound configs; this module has none
    public void injectConfigs(Map<Class<?>, Object> configs) {
        // no configuration records in this module
    }
}
