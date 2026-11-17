package io.tiko.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * The async work of one booted container that has been handed over and has not finished (#721): a
 * task given to its event executor counts from the moment it is submitted until it has run, and a
 * retried handler counts while it waits out its backoff. {@code close()} waits for this to reach
 * zero before it tears the singletons down.
 *
 * <p>A task counts once it is {@linkplain #track tracked}. It stops counting when it has run, when
 * its submission throws, or when whoever discards it says so through {@link #dropped}: the
 * framework's rejection handlers and its {@code shutdownNow()} do. A supplied executor that
 * discards a task without throwing leaves the count up, and a wait on it runs to its deadline.
 */
final class PendingDeliveries {

    private static final long POLL_NANOS = 1_000_000L;

    private final AtomicInteger pending = new AtomicInteger();

    /** How many counted tasks each thread is inside of right now; see {@link #await}. */
    private final Map<Thread, Integer> runningOn = new ConcurrentHashMap<>();

    /** A task that stops counting when it is discarded without having run. */
    interface Droppable {
        void dropped();
    }

    /** Tells {@code task} that it will never run, if it is one that counts. */
    static void dropped(Runnable task) {
        if (task instanceof Droppable droppable) {
            droppable.dropped();
        }
    }

    /** Counts {@code task} from now until the returned task has run or been released. */
    Tracked track(Runnable task) {
        pending.incrementAndGet();
        return new Tracked(task, this);
    }

    /** Counts one more piece of work, for the caller to {@link #release()} exactly once. */
    void retain() {
        pending.incrementAndGet();
    }

    void release() {
        pending.decrementAndGet();
    }

    int count() {
        return pending.get();
    }

    /**
     * Waits until nothing is pending or {@code deadlineNanos} ({@link System#nanoTime()}) has
     * passed. An interrupted caller still waits: its interrupt is cleared for the wait and restored
     * afterwards, as is one that arrives during it. A caller that is itself inside a counted task,
     * a handler that closes the container, does not wait for that task.
     *
     * @return whether nothing else is pending
     */
    boolean await(long deadlineNanos) {
        int own = runningOn.getOrDefault(Thread.currentThread(), 0);
        boolean interrupted = Thread.interrupted();
        try {
            while (pending.get() > own && System.nanoTime() - deadlineNanos < 0) {
                LockSupport.parkNanos(POLL_NANOS);
                interrupted |= Thread.interrupted();
            }
            return pending.get() <= own;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A counted task: released when it has run, or when it is dropped or its submission fails. */
    static final class Tracked implements Runnable, Droppable {
        private final Runnable task;
        private final PendingDeliveries owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private Tracked(Runnable task, PendingDeliveries owner) {
            this.task = task;
            this.owner = owner;
        }

        @Override
        public void run() {
            var thread = Thread.currentThread();
            owner.runningOn.merge(thread, 1, Integer::sum);
            try {
                task.run();
            } finally {
                owner.runningOn.computeIfPresent(thread, (ignored, depth) -> depth == 1 ? null : depth - 1);
                releaseOnce();
            }
        }

        @Override
        public void dropped() {
            releaseOnce();
        }

        private void releaseOnce() {
            if (released.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }
}
