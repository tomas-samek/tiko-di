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

    /**
     * How long a closed boot's event-executor workers get to leave their tasks after
     * {@code shutdownNow()}, and, once the executor has terminated, how long they then get to exit.
     */
    private static final Duration EXECUTOR_GRACE = Duration.ofSeconds(1);

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
     * Boots a test container for {@code owner}. When the boot fails, the container (if one was
     * created) is closed and the executor created for it is shut down before the failure propagates.
     */
    @SuppressWarnings("java:S1181") // any boot failure, Error included, is rethrown unchanged after the cleanup
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
            // Wire the executor in so RecordingEventBus.awaitAsyncDispatch(Duration) works.
            ((RecordingEventBus) container.getEventBus()).setEventExecutor(container.getEventExecutor());
        } catch (Throwable bootFailure) {
            releaseAfterFailedBoot(container, executor, bootFailure);
            throw bootFailure;
        }
        return new BootedContainer(owner, container, executor, reported);
    }

    /**
     * Closes the container of a failed boot, when there is one, and always shuts its executor down. A
     * close failure is added to {@code bootFailure} as suppressed rather than replacing it.
     */
    @SuppressWarnings("java:S1181") // a close failure, Error included, must not replace the boot failure
    private static void releaseAfterFailedBoot(
            Container container, CountingThreadPoolExecutor executor, Throwable bootFailure) {
        try {
            if (container != null) {
                container.close();
            }
        } catch (Throwable closeFailure) {
            if (closeFailure != bootFailure) {
                bootFailure.addSuppressed(closeFailure);
            }
        } finally {
            executor.shutdownNow();
        }
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

    /**
     * Interrupts the executor's workers and gives them {@link #EXECUTOR_GRACE} to leave their tasks.
     * Once the executor has terminated, joins each worker for up to another {@link #EXECUTOR_GRACE}:
     * a terminated executor can still have workers finishing their exit, and those are not leaks. A
     * clean close returns as soon as the workers are gone.
     *
     * <p>A closing thread that arrives interrupted (a test that left its interrupt flag set) still
     * gives the workers the whole grace: the flag is cleared for the waits and restored afterwards.
     */
    private void releaseExecutor() {
        executor.shutdownNow();
        var arrivedInterrupted = Thread.interrupted();
        try {
            if (awaitTermination()) {
                var deadline = System.nanoTime() + EXECUTOR_GRACE.toNanos();
                for (var worker : executor.liveThreads()) {
                    joinUntil(worker, deadline);
                }
            }
        } catch (InterruptedException e) {
            // Interrupted during the waits: stop waiting and keep the flag for the caller.
            Thread.currentThread().interrupt();
        } finally {
            if (arrivedInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean awaitTermination() throws InterruptedException {
        return executor.awaitTermination(EXECUTOR_GRACE.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static void joinUntil(Thread thread, long deadline) throws InterruptedException {
        var remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            thread.join(Duration.ofNanos(remaining));
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
