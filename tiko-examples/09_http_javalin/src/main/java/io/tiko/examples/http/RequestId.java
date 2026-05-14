package io.tiko.examples.http;

/**
 * Per-request correlation ID. The interface exists so a SINGLETON consumer
 * (e.g., TicketHttpRoutes) can have a REQUEST-scoped implementation injected
 * — Tiko generates a proxy at compile time that delegates to the current
 * scope's instance.
 */
public interface RequestId {
    String value();
}
