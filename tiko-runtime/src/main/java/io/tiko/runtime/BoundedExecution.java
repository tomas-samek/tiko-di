package io.tiko.runtime;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
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
     * Returns normally if the observer returns normally. Singleton teardown uses {@link TeardownErrors}
     * to defer observer failures until all cleanup has run.
     *
     * <ul>
     *   <li>{@code timeout == null} → run inline on the calling thread (today's behavior, zero
     *       overhead); a thrown {@link Throwable} is routed.</li>
     *   <li>otherwise → run on a single-use daemon thread bounded by {@code timeout}, counted from
     *       the moment the hook starts, so a hook always runs before a timeout is reported (#699).
     *       On overrun the worker is interrupted via {@link Future#cancel(boolean)} and a
     *       {@link TimeoutException}-caused failure is routed; a thrown {@link Throwable} is routed
     *       with its original cause (unwrapped from {@link ExecutionException}). {@link Duration#ZERO}
     *       starts the hook, then interrupts it and routes a timeout unless it completed instantly.</li>
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
            var started = new CountDownLatch(1);
            Future<?> future = executor.submit(() -> {
                started.countDown();
                task.run();
                return null;
            });
            try {
                awaitHook(started, future, timeout.toNanos());
            } catch (TimeoutException te) {
                future.cancel(true);
                errorHandler.onError(failure.apply(te));
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                errorHandler.onError(failure.apply(cause));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Waits for the hook to start and then to finish within {@code timeoutNanos}.
     *
     * <p>The bound is the hook's own time (#699): on a busy machine the dedicated thread can start
     * late, and a bound counted from submit would cancel the hook unrun.
     *
     * <p>An interrupt of the calling thread does not end the wait (#709): a thread that closes the
     * container while interrupted, such as a worker stopped by {@code shutdownNow()}, still tears
     * every component down, each under its bound. The flag is cleared for the wait and set again
     * before this method returns or throws.
     */
    private static void awaitHook(CountDownLatch started, Future<?> future, long timeoutNanos)
            throws TimeoutException, ExecutionException {
        boolean interrupted = Thread.interrupted();
        try {
            long startNanos = 0;
            boolean running = false;
            while (true) {
                try {
                    if (!running) {
                        started.await();
                        startNanos = System.nanoTime();
                        running = true;
                    }
                    long remaining = timeoutNanos - (System.nanoTime() - startNanos);
                    future.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Taken from the closing or rolling-back container's {@link OwnedThreads} when one is bound
     * (#634, #651), else untracked.
     */
    private static Thread daemonThread(Runnable r) {
        Thread t = OwnedThreads.boundOr(Thread::new).newThread(r);
        t.setName("tiko-teardown");
        t.setDaemon(true);
        return t;
    }

    static Throwable retainFailure(Throwable primary, Throwable next) {
        if (primary == null) {
            return next;
        }
        if (primary != next) {
            primary.addSuppressed(next);
        }
        return primary;
    }

    /** Carries an observer failure across the internal module boundary without losing its identity. */
    static final class ModuleTeardownFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ModuleTeardownFailure(Throwable cause) {
            super("Module teardown observer failed", cause);
        }
    }

    /** Collects observer failures across the callbacks of one generated singleton teardown. */
    public static final class TeardownErrors implements ErrorHandler {
        private final ErrorHandler delegate;
        private Throwable primaryFailure;

        /** Starts with the original startup failure during rollback, or {@code null} during regular shutdown. */
        public TeardownErrors(ErrorHandler delegate, Throwable startupFailure) {
            this.delegate = delegate;
            this.primaryFailure = startupFailure;
        }

        @Override
        @SuppressWarnings("java:S1181") // Preserve errors too and defer propagation until cleanup finishes.
        public void onError(ErrorContext context) {
            try {
                delegate.onError(context);
            } catch (Throwable observerFailure) {
                var handledFailure = context.cause();
                // Avoid self-suppression and a cycle back to the primary failure.
                if (observerFailure != handledFailure && handledFailure != primaryFailure) {
                    observerFailure.addSuppressed(handledFailure);
                }
                primaryFailure = retainFailure(primaryFailure, observerFailure);
            }
        }

        /** Rethrows the observer failure, deferring its delivery to the aggregator for an internal module. */
        public void rethrowIfFailed(boolean aggregatedModule) {
            if (primaryFailure != null) {
                if (aggregatedModule) {
                    throw new ModuleTeardownFailure(primaryFailure);
                }
                throw Unchecked.<RuntimeException>sneakyThrow(primaryFailure);
            }
        }
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
