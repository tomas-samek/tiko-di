package io.tiko.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
 *
 * <p><strong>Retry thread.</strong> A retried async handler waits out its backoff on this owner's
 * {@code tiko-event-retry} thread ({@link #scheduleRetry}), created on first use, so the attempt it
 * re-submits never lands on an untracked thread (#653). Closing stops it before the exit grace
 * ({@link #reportSurvivors}).
 */
final class OwnedThreads implements ThreadFactory {

    /** How long {@code close()} waits, after its bounded waits, for tracked threads to exit. */
    static final Duration THREAD_EXIT_GRACE = Duration.ofSeconds(1);

    /** The least time the work of {@link #stopWork} gets to stop in. */
    static final Duration WORK_STOP_GRACE = Duration.ofSeconds(1);

    private static final ThreadLocal<OwnedThreads> BOUND = new ThreadLocal<>();

    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    private final Set<Thread> threads = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final AtomicBoolean reported = new AtomicBoolean();

    /** Guards {@link #retryScheduler} and {@link #retriesStopped}. */
    private final Object retryLock = new Object();

    private final AtomicInteger retryThreadCount = new AtomicInteger();

    private ScheduledThreadPoolExecutor retryScheduler;
    private boolean retriesStopped;

    private final PendingDeliveries deliveries = new PendingDeliveries();

    /** The virtual threads of {@link #virtualThreads} that are running their task right now (#678). */
    private final Set<Thread> runningVirtualThreads = ConcurrentHashMap.newKeySet();

    /** Guards {@link #work} and {@link #workStopped}. */
    private final Object workLock = new Object();

    /** What other Tiko modules keep for this boot, by type, in the order it was created. */
    private final Map<Class<?>, ContainerWork.Stoppable> work = new LinkedHashMap<>();

    private boolean workStopped;

    /** The async work of this boot that has been handed over and has not finished (#721). */
    PendingDeliveries deliveries() {
        return deliveries;
    }

    /**
     * Drops the retries still waiting out their backoff ({@link #stopRetries()}), then waits until
     * nothing is {@linkplain #deliveries() pending} or {@code deadlineNanos} has passed.
     *
     * @return whether nothing is pending
     */
    boolean awaitDeliveries(long deadlineNanos) {
        stopRetries();
        return deliveries.await(deadlineNanos);
    }

    @Override
    public Thread newThread(Runnable task) {
        var thread = Thread.ofPlatform().daemon().unstarted(task);
        threads.add(thread);
        return thread;
    }

    /**
     * A factory of virtual threads named {@code namePrefix} + a number (#678). A coroutine
     * dispatcher starts one for every resume, so they are short-lived and many: each is known here
     * only while it runs its task, which is all {@link #stillAlive} needs.
     */
    ThreadFactory virtualThreads(String namePrefix) {
        var unnamed = Thread.ofVirtual().name(namePrefix, 1).factory();
        return task -> unnamed.newThread(() -> {
            var self = Thread.currentThread();
            runningVirtualThreads.add(self);
            try {
                task.run();
            } finally {
                runningVirtualThreads.remove(self);
            }
        });
    }

    /**
     * The one instance of {@code type} kept for this boot, created on first use (#678).
     *
     * @return the instance, or empty once {@link #stopWork} has run
     */
    <T extends ContainerWork.Stoppable> Optional<T> work(Class<T> type, Supplier<T> create) {
        synchronized (workLock) {
            if (workStopped) {
                return Optional.empty();
            }
            return Optional.of(type.cast(work.computeIfAbsent(type, ignored -> create.get())));
        }
    }

    /**
     * Stops what other Tiko modules run for this boot (#678): {@code close()} calls it after its
     * wait for the pending deliveries, and before it stops delivering events. Each instance is
     * stopped once and given until {@code deadlineNanos}, and at least {@link #WORK_STOP_GRACE}: a
     * cancelled coroutine needs time to run its cleanup even when the wait before used up the
     * shutdown timeout. An instance that throws does not keep the others from being stopped.
     *
     * <p>What the stopped work handed over on its way out, an event a cancelled coroutine published
     * to an async handler for one, is then waited for until {@code deadlineNanos}.
     *
     * @return whether nothing is pending afterwards
     */
    boolean stopWork(long deadlineNanos) {
        List<ContainerWork.Stoppable> toStop;
        synchronized (workLock) {
            if (workStopped) {
                return deliveries.count() == 0;
            }
            workStopped = true;
            toStop = new ArrayList<>(work.values());
        }
        if (toStop.isEmpty()) {
            return deliveries.count() == 0;
        }
        long earliest = System.nanoTime() + WORK_STOP_GRACE.toNanos();
        long deadline = deadlineNanos - earliest < 0 ? earliest : deadlineNanos;
        for (var stoppable : toStop) {
            try {
                stoppable.stop(deadline);
            } catch (RuntimeException e) {
                LoggerHolder.LOG.log(System.Logger.Level.WARNING, "Stopping the container's work threw", e);
            }
        }
        return deliveries.await(deadlineNanos);
    }

    /**
     * Runs {@code resubmit} after {@code delayNanos} on this owner's retry thread, created from
     * this factory on first use and named {@code tiko-event-retry-N} (#653). A retried async
     * handler re-submits its next attempt from there, so a {@code CALLER_RUNS} attempt that runs on
     * the re-submitting thread runs on a tracked one.
     *
     * @return {@code false} once {@link #stopRetries()} has run, so {@code resubmit} will never run
     */
    boolean scheduleRetry(Runnable resubmit, long delayNanos) {
        synchronized (retryLock) {
            if (retriesStopped) {
                return false;
            }
            if (retryScheduler == null) {
                retryScheduler = new ScheduledThreadPoolExecutor(1, this::newRetryThread);
            }
            // Pending while it waits out its backoff and while it re-submits (#721).
            deliveries.retain();
            retryScheduler.schedule(
                    () -> {
                        try {
                            resubmit.run();
                        } finally {
                            deliveries.release();
                        }
                    },
                    delayNanos,
                    TimeUnit.NANOSECONDS);
            return true;
        }
    }

    private Thread newRetryThread(Runnable task) {
        var thread = newThread(task);
        thread.setName("tiko-event-retry-" + retryThreadCount.incrementAndGet());
        return thread;
    }

    /**
     * Stops the retry thread: no retry is scheduled from now on, and those still waiting out their
     * backoff are dropped with one WARNING. A retry already running keeps its thread until it
     * returns; an idle thread exits at once, so it is never reported as outliving close.
     */
    void stopRetries() {
        ScheduledThreadPoolExecutor scheduler;
        synchronized (retryLock) {
            retriesStopped = true;
            scheduler = retryScheduler;
        }
        if (scheduler == null) {
            return;
        }
        var dropped = 0;
        for (var pending : scheduler.getQueue().toArray(new Runnable[0])) {
            if (pending instanceof Future<?> future && future.cancel(false)) {
                deliveries.release();
                dropped++;
            }
        }
        scheduler.shutdown();
        if (dropped > 0) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    (dropped == 1 ? "1 pending async retry was" : dropped + " pending async retries were")
                            + " dropped: the container is shutting down.");
        }
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
        snapshot.addAll(runningVirtualThreads);
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
     * reports nothing. Stops the retry thread first ({@link #stopRetries()}), so an idle one has
     * exited before the grace runs out.
     *
     * <p>The handler never masks the failure already leaving {@code close()}: when
     * {@code primaryFailure} is set, anything the handler throws is added to it as suppressed and
     * the caller rethrows the primary. Without one, the handler's throwable propagates.
     *
     * @param primaryFailure the shutdown or boot failure the caller is about to rethrow, or {@code null}
     */
    @SuppressWarnings("java:S1181") // An Error from the handler must not replace the primary failure either.
    void reportSurvivors(Consumer<List<Thread>> handler, Throwable primaryFailure) {
        stopRetries();
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

    /**
     * Runs {@code action} with {@code owner} bound to the calling thread, restoring the previous
     * binding. A {@code null} owner binds none for the call, hiding any outer binding, so the factories
     * fall back to untracked threads.
     */
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
