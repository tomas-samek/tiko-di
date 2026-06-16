package io.tiko.annotations;

/**
 * How the delay between {@code @EventHandler(retries = ...)} attempts grows (#108).
 *
 * <p>The base delay is {@code @EventHandler(backoff = ...)}; this strategy decides how it
 * scales across successive retries. Both are honoured only for asynchronous handlers —
 * retries are async-only (see {@link EventHandler#retries()}).
 */
public enum BackoffStrategy {

    /** Same delay before every retry: {@code backoff}, {@code backoff}, {@code backoff}, … */
    FIXED,

    /**
     * Delay doubles each retry: {@code backoff}, {@code 2 × backoff}, {@code 4 × backoff}, …
     * Eases pressure on a struggling downstream by spacing retries out geometrically.
     */
    EXPONENTIAL
}
