package io.tiko.test;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * One {@code @TikoTest} boot: the container, the event executor the extension supplied to it, and
 * the threads the container reported as outliving its close (#637).
 *
 * <p>Stored in the extension store as a {@link ExtensionContext.Store.CloseableResource}, so JUnit
 * closes it even when {@code afterEach} / {@code afterAll} never run. {@link #close()} closes the
 * container, then always releases the executor, then fails with an {@link AssertionError} naming
 * the threads still running. Survivors come only from this boot's report and this boot's executor,
 * never from a scan of the JVM's threads, so a test is never blamed for another test's threads.
 */
final class BootedContainer implements ExtensionContext.Store.CloseableResource {

    /** How long a closed boot's event-executor workers get to exit after {@code shutdownNow()}. */
    private static final Duration EXECUTOR_GRACE = Duration.ofSeconds(1);

    /** The minimum wait for a worker of a terminated executor, which is already on its way out. */
    private static final Duration EXITING_WORKER_GRACE = Duration.ofMillis(50);

    private static final int FRAMES_PER_THREAD = 3;

    private final String owner;
    private final Container container;
    private final CountingThreadPoolExecutor executor;
    private final List<Thread> reported;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param owner names the test in the leak message: {@code Class.method}, or {@code Class} for a
     *     per-class container
     * @param reported the list the container's {@code onThreadsOutlivingClose} handler fills
     */
    BootedContainer(String owner, Container container, CountingThreadPoolExecutor executor, List<Thread> reported) {
        this.owner = owner;
        this.container = container;
        this.executor = executor;
        this.reported = reported;
    }

    /**
     * Boots a test container for {@code owner}. When the boot fails, the executor created for it is
     * shut down before the failure propagates.
     */
    static BootedContainer boot(String owner) {
        // Supply a CountingThreadPoolExecutor so awaitAsyncDispatch(Duration) detects drain
        // deterministically (#443). Because it is supplied, the container does not own it —
        // close() shuts it down.
        var executor = CountingThreadPoolExecutor.withFrameworkDefaults();
        // The handler only collects: close() turns the survivors into the test's failure itself.
        var reported = new CopyOnWriteArrayList<Thread>();
        // A @TikoTest container is a test container: apply @TestComponent wiring (#497).
        var options = TikoOptions.builder()
                .eventBusDecorator(RecordingEventBus::new)
                .eventExecutor(executor)
                .testWiring(true)
                .onThreadsOutlivingClose(reported::addAll)
                .build();
        Container container = null;
        try {
            container = Tiko.create(options);
        } finally {
            if (container == null) {
                executor.shutdownNow();
            }
        }
        var booted = new BootedContainer(owner, container, executor, reported);
        // Wire the executor in so RecordingEventBus.awaitAsyncDispatch(Duration) works.
        booted.bus().setEventExecutor(container.getEventExecutor());
        return booted;
    }

    Container container() {
        return container;
    }

    RecordingEventBus bus() {
        return (RecordingEventBus) container.getEventBus();
    }

    /**
     * Closes the container, then shuts the executor down whether or not that close failed, then
     * reports the threads still running. When the close failed, its failure propagates with the
     * leak report added as suppressed; otherwise the leak report is thrown. Closing twice does
     * nothing.
     *
     * @throws AssertionError naming each thread still running and its top frames
     */
    @Override
    // S1181: an Error from close() is rethrown unchanged, after the executor is released and the leak
    // report is attached to it; it is never swallowed.
    @SuppressWarnings("java:S1181")
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            container.close();
        } catch (Throwable closeFailure) {
            releaseExecutor();
            leakError().ifPresent(closeFailure::addSuppressed);
            throw closeFailure;
        }
        releaseExecutor();
        var leak = leakError();
        if (leak.isPresent()) {
            throw leak.get();
        }
    }

    /** Interrupts the executor's workers and gives them {@link #EXECUTOR_GRACE} to exit. */
    private void releaseExecutor() {
        executor.shutdownNow();
        try {
            if (executor.awaitTermination(EXECUTOR_GRACE.toNanos(), TimeUnit.NANOSECONDS)) {
                // Terminated: every worker has left its task; wait out the last instants of their exit.
                for (var worker : executor.liveThreads()) {
                    worker.join(EXITING_WORKER_GRACE);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<AssertionError> leakError() {
        var survivors = new LinkedHashSet<Thread>(reported);
        survivors.addAll(executor.liveThreads());
        survivors.removeIf(thread -> !thread.isAlive());
        if (survivors.isEmpty()) {
            return Optional.empty();
        }
        var message = new StringBuilder(owner)
                .append(": ")
                .append(survivors.size())
                .append(" thread(s) still running after its container closed:");
        for (var thread : survivors) {
            message.append("\n  ").append(thread.getName());
            var stack = thread.getStackTrace();
            for (var i = 0; i < Math.min(FRAMES_PER_THREAD, stack.length); i++) {
                message.append("\n    at ").append(stack[i]);
            }
        }
        return Optional.of(new AssertionError(message.toString()));
    }
}
