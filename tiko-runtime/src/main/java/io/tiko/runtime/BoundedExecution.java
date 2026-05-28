package io.tiko.runtime;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Runs a teardown hook under an optional time bound, routing any failure — thrown or timed-out —
 * to an {@link ErrorHandler} via a caller-supplied {@link ErrorContext} factory.
 *
 * <p>The bounding logic lives here (in unit-tested runtime code) so the generated container
 * shutdown stays a thin one-line delegation per hook. The same primitive is intended to back the
 * rest of the resiliency cluster (#107+): each phase supplies its own timeout knob and failure
 * factory, with the on-timeout <em>policy</em> differing per phase — teardown logs and continues,
 * a future {@code @PostConstruct} bound would fail fast, {@code @EventHandler} routes-and-retries.
 */
public final class BoundedExecution {

    private BoundedExecution() {}

    /**
     * Runs {@code task}, routing any failure to {@code errorHandler} as {@code failure.apply(cause)}.
     * Always returns normally — failures are routed, never propagated — so a caller iterating over
     * hooks proceeds to the next one.
     *
     * <ul>
     *   <li>{@code timeout == null} → run inline on the calling thread (today's behavior, zero
     *       overhead); a thrown {@link Throwable} is routed.</li>
     *   <li>otherwise → run on a single-use daemon thread bounded by {@code timeout}. On overrun the
     *       worker is interrupted via {@link Future#cancel(boolean)} and a
     *       {@link TimeoutException}-caused failure is routed; a thrown {@link Throwable} is routed
     *       with its original cause (unwrapped from {@link ExecutionException}). {@link Duration#ZERO}
     *       gives the hook no time and routes a timeout immediately unless it completes instantly.</li>
     * </ul>
     *
     * @param task the hook to run; may throw a checked exception (e.g. {@link AutoCloseable#close()})
     * @param timeout the bound, or {@code null} for unbounded inline execution
     * @param errorHandler the channel a failure is routed through
     * @param failure maps the failing {@link Throwable} (thrown cause or {@link TimeoutException}) to
     *     the {@link ErrorContext} to route
     */
    // S2095 (close the ExecutorService via try-with-resources): deliberately not done. The executor
    // is shut down with shutdownNow() in the finally below — close()/try-with-resources would call
    // shutdown() + awaitTermination, blocking on a hung task, which is exactly the case this method
    // exists to bound. Forceful shutdownNow() is the correct cleanup here.
    @SuppressWarnings("java:S2095")
    public static void run(
            CheckedRunnable task,
            Duration timeout,
            ErrorHandler errorHandler,
            Function<Throwable, ErrorContext> failure) {
        if (timeout == null) {
            try {
                task.run();
            } catch (Throwable t) {
                errorHandler.onError(failure.apply(t));
            }
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(BoundedExecution::daemonThread);
        try {
            Future<?> future = executor.submit(() -> {
                task.run();
                return null;
            });
            try {
                future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                errorHandler.onError(failure.apply(te));
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                errorHandler.onError(failure.apply(cause));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.cancel(true);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Thread daemonThread(Runnable r) {
        Thread t = new Thread(r, "tiko-teardown");
        t.setDaemon(true);
        return t;
    }

    /**
     * A {@link Runnable} that may throw a checked exception — teardown hooks include
     * {@link AutoCloseable#close()} ({@code throws Exception}) and arbitrary {@code @PreDestroy}
     * methods. The thrown {@link Throwable} is routed via the failure factory, never propagated.
     */
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
