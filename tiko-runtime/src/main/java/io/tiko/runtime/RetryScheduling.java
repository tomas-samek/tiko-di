package io.tiko.runtime;

/**
 * An event executor whose async retries wait out their backoff on the booted container's own
 * retry thread rather than on the JDK's shared delay scheduler (#653). A retry re-submits its next
 * attempt from that thread, so when a {@code CALLER_RUNS} queue is full and the attempt runs there,
 * it runs on a thread the container tracks and reports if it outlives {@code close()}.
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
}
