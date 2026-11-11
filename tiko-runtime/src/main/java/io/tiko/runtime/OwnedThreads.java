package io.tiko.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The threads one booted container starts, and the report of those still running after it closed
 * (#634). Creates daemon platform threads and tracks them weakly, so a thread that has exited and
 * been dropped costs nothing.
 *
 * <p><strong>Routing without a parameter.</strong> The generated container builds its default
 * event executor in its constructor ({@link DefaultEventExecutorFactory#create(int, OverflowPolicy,
 * int, int, Duration)}) and starts teardown threads ({@link BoundedExecution}) from its
 * {@code shutdown()}, and from its {@code start()} when a failed start rolls back (#651). Neither
 * call can be handed the owner without changing generated code, so the runtime binds the owner to
 * the calling thread around exactly those moments ({@link #callBound}) and the two factories read
 * it with {@link #boundOr}. This class is the only
 * place that binding lives. The executor captures the factory once, at creation, because its worker
 * threads are started later, from other threads.
 */
final class OwnedThreads implements ThreadFactory {

    /** How long {@code close()} waits, after its bounded waits, for tracked threads to exit. */
    static final Duration THREAD_EXIT_GRACE = Duration.ofSeconds(1);

    private static final ThreadLocal<OwnedThreads> BOUND = new ThreadLocal<>();

    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    private final Set<Thread> threads = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final AtomicBoolean reported = new AtomicBoolean();

    @Override
    public Thread newThread(Runnable task) {
        var thread = Thread.ofPlatform().daemon().unstarted(task);
        threads.add(thread);
        return thread;
    }

    /**
     * Joins every live tracked thread within one shared {@code grace} deadline and returns those
     * still alive, oldest first. The calling thread is never reported: {@code close()} may run on
     * a thread the container started.
     *
     * <p>An interrupted caller still gives the threads the whole grace: its interrupt is cleared
     * for the joins and restored afterwards, as is one that arrives during them.
     */
    List<Thread> stillAlive(Duration grace) {
        List<Thread> snapshot;
        synchronized (threads) {
            snapshot = new ArrayList<>(threads);
        }
        snapshot.sort(Comparator.comparingLong(Thread::threadId));
        var deadline = System.nanoTime() + grace.toNanos();
        var current = Thread.currentThread();
        var interrupted = Thread.interrupted();
        var alive = new ArrayList<Thread>();
        try {
            for (var thread : snapshot) {
                if (thread == current || !thread.isAlive()) {
                    continue;
                }
                interrupted |= joinUntil(thread, deadline);
                if (thread.isAlive()) {
                    alive.add(thread);
                }
            }
        } finally {
            if (interrupted) {
                current.interrupt();
            }
        }
        return List.copyOf(alive);
    }

    /**
     * Hands the threads still alive after {@link #THREAD_EXIT_GRACE} to {@code handler}, or logs
     * them when it is {@code null}. Runs at most once per owner, so a second {@code close()}
     * reports nothing.
     *
     * <p>The handler never masks the failure already leaving {@code close()}: when
     * {@code primaryFailure} is set, anything the handler throws is added to it as suppressed and
     * the caller rethrows the primary. Without one, the handler's throwable propagates.
     *
     * @param primaryFailure the shutdown or boot failure the caller is about to rethrow, or {@code null}
     */
    @SuppressWarnings("java:S1181") // An Error from the handler must not replace the primary failure either.
    void reportSurvivors(Consumer<List<Thread>> handler, Throwable primaryFailure) {
        if (!reported.compareAndSet(false, true)) {
            return;
        }
        var survivors = stillAlive(THREAD_EXIT_GRACE);
        if (survivors.isEmpty()) {
            return;
        }
        if (handler == null) {
            logSurvivors(survivors);
            return;
        }
        try {
            handler.accept(survivors);
        } catch (Throwable handlerFailure) {
            if (primaryFailure == null) {
                throw handlerFailure;
            }
            if (handlerFailure != primaryFailure) {
                primaryFailure.addSuppressed(handlerFailure);
            }
        }
    }

    /** The default report: one WARNING naming each surviving thread and the frame it is in. */
    static void logSurvivors(List<Thread> survivors) {
        var described = survivors.stream().map(OwnedThreads::describe).collect(Collectors.joining(", "));
        LoggerHolder.LOG.log(
                System.Logger.Level.WARNING,
                "Container closed, but " + survivors.size() + " thread(s) it started are still running: " + described);
    }

    private static String describe(Thread thread) {
        var stack = thread.getStackTrace();
        return thread.getName() + " (at " + (stack.length > 0 ? stack[0] : "unknown frame") + ")";
    }

    /**
     * Waits for {@code thread} until {@code deadline} and returns whether the wait was interrupted.
     * The interrupt is reported back rather than restored, so the remaining joins still wait; the
     * caller restores it.
     */
    @SuppressWarnings("java:S2142") // not swallowed: returned to stillAlive, which restores the interrupt
    private static boolean joinUntil(Thread thread, long deadline) {
        var interrupted = false;
        var remaining = deadline - System.nanoTime();
        while (remaining > 0 && thread.isAlive()) {
            try {
                thread.join(Duration.ofNanos(remaining));
            } catch (InterruptedException e) {
                interrupted = true;
            }
            remaining = deadline - System.nanoTime();
        }
        return interrupted;
    }

    /** An action run while an owner is bound; may throw what the bound call throws. */
    @FunctionalInterface
    interface BoundAction<T, E extends Exception> {
        T run() throws E;
    }

    /** Runs {@code action} with {@code owner} bound to the calling thread, restoring the previous binding. */
    static <T, E extends Exception> T callBound(OwnedThreads owner, BoundAction<T, E> action) throws E {
        var previous = BOUND.get();
        BOUND.set(owner);
        try {
            return action.run();
        } finally {
            if (previous == null) {
                BOUND.remove();
            } else {
                BOUND.set(previous);
            }
        }
    }

    /** {@link #callBound} for an action without a result. */
    static void runBound(OwnedThreads owner, Runnable action) {
        callBound(owner, () -> {
            action.run();
            return null;
        });
    }

    /** The owner bound to the calling thread, or {@code fallback} when none is. */
    static ThreadFactory boundOr(ThreadFactory fallback) {
        var owner = BOUND.get();
        return owner != null ? owner : fallback;
    }
}
