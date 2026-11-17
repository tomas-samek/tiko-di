package io.tiko.runtime;

/**
 * An event executor whose async retries wait out their backoff on the booted container's own
 * retry thread rather than on the JDK's shared delay scheduler (#653). A retry re-submits its next
 * attempt from that thread, so when a {@code CALLER_RUNS} queue is full and the attempt runs there,
 * it runs on a thread the container tracks and reports if it outlives {@code close()}.
 *
 * <p>It also counts the work handed to it that has not finished ({@link PendingDeliveries}), which
 * is what {@code close()} waits for before it tears the singletons down (#721).
 *
 * <p>Implemented by the executors the generated dispatch code of a {@code Tiko.create(...)} boot
 * sees: the default executor built while the boot's {@link OwnedThreads} is bound, and
 * {@link RetrySchedulingExecutor} over a supplied one.
 */
interface RetryScheduling {

    /**
     * Runs {@code resubmit} after {@code delayNanos} on the container's retry thread.
     *
     * @return {@code false} when the container is closing, so {@code resubmit} will never run
     */
    boolean scheduleRetry(Runnable resubmit, long delayNanos);

    /**
     * Drops the retries still waiting out their backoff, then waits until no task handed to this
     * executor is queued or running, or until {@code deadlineNanos} ({@link System#nanoTime()}).
     *
     * @return whether nothing is pending
     */
    boolean awaitDeliveries(long deadlineNanos);

    /** How many tasks handed to this executor are queued or running, plus the retries in backoff. */
    int pendingDeliveries();
}
