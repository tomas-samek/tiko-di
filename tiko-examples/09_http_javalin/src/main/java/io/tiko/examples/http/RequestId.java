package io.tiko.examples.http;

/**
 * Per-request correlation ID. Implementations are REQUEST-scoped; in this
 * example the bridge resolves the current instance via
 * {@code container.get(RequestId.class)} from inside the open request scope.
 *
 * <p>The interface exists so that — in other shapes of this pattern — a
 * SINGLETON {@code @Component} consumer could receive a REQUEST-scoped
 * implementation through Tiko's compile-time auto-proxy mechanism. See
 * {@code 01_basic_di} for that variant.
 */
public interface RequestId {
    String value();
}
