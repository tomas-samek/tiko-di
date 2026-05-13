package io.tiko;

/**
 * Open permit under {@link ErrorContext} for transport-module errors. Transport modules
 * ({@code tiko-kafka}, future {@code tiko-http}, ...) define concrete record types that
 * implement this interface and add transport-specific fields (topic, partition, request
 * id, ...). Users pattern-match on {@code TransportError} for cross-transport handling,
 * or on the concrete record types for transport-specific handling.
 *
 * <p>This interface is intentionally {@code non-sealed} — adding a new transport must
 * not require an edit in {@code tiko-api}.
 */
public non-sealed interface TransportError extends ErrorContext {

    /**
     * Short transport identifier, e.g. {@code "kafka"}, {@code "http"}, {@code "scheduler"}.
     * Used by generic error-handling code that does not pattern-match on concrete types.
     */
    String transport();
}
