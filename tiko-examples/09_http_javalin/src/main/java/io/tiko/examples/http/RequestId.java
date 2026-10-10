package io.tiko.examples.http;

/**
 * Per-request correlation ID. Implementations are EVENT-scoped (one per unit of
 * work, i.e. per HTTP request here); the bridge resolves the current instance via
 * {@code container.get(RequestId.class)} from inside the request's unit.
 *
 * <p>The interface exists so that — in other shapes of this pattern — a
 * SINGLETON {@code @Component} consumer could receive an EVENT-scoped
 * implementation through Tiko's compile-time auto-proxy mechanism. See
 * {@code 01_basic_di} for that variant.
 */
public interface RequestId {
    String value();
}
