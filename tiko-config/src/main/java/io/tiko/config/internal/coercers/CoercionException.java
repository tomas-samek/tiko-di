// tiko-config/src/main/java/io/tiko/config/internal/coercers/CoercionException.java
package io.tiko.config.internal.coercers;

/** Package-private failure signal used by coercers; surfaces as ConfigError to users. */
public final class CoercionException extends RuntimeException {
    public CoercionException(String message) { super(message); }
}
