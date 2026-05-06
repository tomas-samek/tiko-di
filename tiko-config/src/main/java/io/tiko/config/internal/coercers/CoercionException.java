// tiko-config/src/main/java/io/tiko/config/internal/coercers/CoercionException.java
package io.tiko.config.internal.coercers;

/**
 * Failure signal used by coercers; surfaces as a {@code ConfigError}
 * to users via the binder's BindContext error accumulation.
 *
 * <p>Although the class is {@code public} (so generated binder code in
 * {@code io.tiko.generated.config.*} can construct/observe it), it is
 * not part of the supported public SPI and may move or be hidden in
 * a later release.</p>
 */
public final class CoercionException extends RuntimeException {
    public CoercionException(String message) { super(message); }
}
