package io.tiko.examples.http;

/**
 * Per-request correlation ID. Implementations are EVENT-scoped (one per unit of
 * work, i.e. per HTTP request here). The interface lets the SINGLETON
 * {@link TicketHttpRoutes} take it: Tiko's compile-time auto-proxy resolves the
 * current request's instance on every call.
 */
public interface RequestId {
    String value();
}
