package io.tiko.runtime;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * What a Tiko module that runs work of its own for a booted container asks of that boot (#678).
 * {@code tiko-coroutines} runs the container's coroutines through it: on threads the boot tracks,
 * counted as deliveries {@code close()} waits for, and stopped when the container closes.
 *
 * <p>For Tiko's own modules; applications don't use it.
 */
public final class ContainerWork {

    /** State a module keeps for one boot. */
    public interface Stoppable {

        /**
         * Called once by {@code close()}, after it has waited for the pending deliveries: stops what
         * still runs and waits for it to have stopped.
         *
         * @param deadlineNanos until when to wait, as a {@link System#nanoTime()} value
         */
        void stop(long deadlineNanos);
    }

    private final OwnedThreads owner;

    private ContainerWork(OwnedThreads owner) {
        this.owner = owner;
    }

    /**
     * The boot that dispatches its async events through {@code eventExecutor}.
     *
     * @param eventExecutor what {@code container.getEventExecutor()} returns
     * @throws IllegalStateException if the executor is not that of a container booted by {@code Tiko.create(...)}
     */
    public static ContainerWork of(ExecutorService eventExecutor) {
        if (eventExecutor instanceof RetryScheduling tracked && tracked.owner() != null) {
            return new ContainerWork(tracked.owner());
        }
        throw new IllegalStateException("This is not the event executor of a container booted by Tiko.create(...): "
                + "only such a container owns threads and work that end when it closes.");
    }

    /**
     * A factory of virtual threads named {@code namePrefix} + a number. The boot knows each one
     * while it runs, so one that is still running after {@code close()} is reported with the other
     * threads that outlive it.
     */
    public ThreadFactory virtualThreads(String namePrefix) {
        return owner.virtualThreads(namePrefix);
    }

    /**
     * Counts one more pending delivery: {@code close()} waits for it, and
     * {@code pendingAsyncDeliveries()} reports it.
     *
     * @return what releases it; only its first run does anything
     */
    public Runnable retainDelivery() {
        owner.deliveries().retain();
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                owner.deliveries().release();
            }
        };
    }

    /**
     * How much accepted work of one kind the boot lets wait, {@code TikoOptions.queueCapacity()}
     * (#679). A module that runs work of its own applies it to that work.
     */
    public int queueCapacity() {
        return owner.queueCapacity();
    }

    /** What the boot does with work past {@link #queueCapacity()}: {@code TikoOptions.onOverflow()} (#679). */
    public OverflowPolicy onOverflow() {
        return owner.overflowPolicy();
    }

    /**
     * Whether the calling thread is one the boot created: a worker of its default event executor or
     * one of its {@linkplain #virtualThreads virtual threads}. Such a thread must not wait for work
     * of the same boot to finish: that work may be waiting for it (#727).
     */
    public boolean isOwnThread() {
        return owner.isOwnThread();
    }

    /**
     * The boot's one instance of {@code type}, created on first use and stopped by {@code close()}.
     *
     * @return the instance, or empty once {@code close()} has begun stopping the boot's work
     */
    public <T extends Stoppable> Optional<T> stoppedAtClose(Class<T> type, Supplier<T> create) {
        return owner.work(type, create);
    }
}
