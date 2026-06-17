package io.tiko.runtime;

/**
 * What the framework's default async event executor does when its bounded work queue is full
 * (#109). Configured via {@code TikoOptions.onOverflow(...)}; only affects the framework default
 * executor — a user-supplied {@code TikoOptions.eventExecutor(...)} brings its own queue and
 * rejection policy.
 *
 * <p>Every policy degrades to a logged drop once the executor is shutting down, so a backpressure
 * policy can never hang or fail shutdown (the {@code ShutdownAwareCallerRunsPolicy} contract, #346).
 */
public enum OverflowPolicy {

    /**
     * Default. The overflowing task runs on the publisher's thread — natural backpressure that
     * slows the producer without dropping work. This is the framework's historical behaviour.
     */
    CALLER_RUNS,

    /** The publisher blocks until the queue has space. Strongest backpressure; can stall the producer. */
    BLOCK,

    /** The overflowing event is dropped and a {@code WARNING} is logged. Favours liveness over delivery. */
    DROP,

    /**
     * {@code publish(...)} throws {@link io.tiko.EventQueueOverflowException} on the publisher's
     * thread, letting the caller decide what to do. ({@code ROUTE_TO_DLQ} — route the overflow to a
     * dead-letter channel — is deferred until that channel ships in #111.)
     */
    THROW
}
