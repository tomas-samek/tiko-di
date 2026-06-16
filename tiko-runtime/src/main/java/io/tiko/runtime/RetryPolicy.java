package io.tiko.runtime;

import io.tiko.annotations.BackoffStrategy;

/**
 * The resolved {@code @EventHandler(retries = ..., backoff = ..., backoffStrategy = ...)} knobs for
 * one handler (#108), plus the composed per-attempt {@code timeout} budget (#107). Constructed by
 * generated {@code EventRegistry} code and threaded through {@link EventChainContext}'s retry loop
 * so the loop carries one cohesive value rather than four loose parameters.
 *
 * <p>Not part of the public API.
 *
 * @param retries        retries after the initial attempt (so attempts made = {@code retries + 1})
 * @param backoffNanos   base delay between attempts, or {@code 0} for immediate retries
 * @param backoffStrategy how {@code backoffNanos} grows across attempts
 * @param timeoutNanos   per-attempt timeout, or {@code 0} for no timeout
 */
public record RetryPolicy(int retries, long backoffNanos, BackoffStrategy backoffStrategy, long timeoutNanos) {}
