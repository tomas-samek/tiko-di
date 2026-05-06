// tiko-config/src/main/java/io/tiko/config/internal/coercers/TypeCoercer.java
package io.tiko.config.internal.coercers;

/**
 * Strategy for coercing a YAML scalar (or composite) into a target Java type.
 * Implementations throw {@link CoercionException} on failure; generated binders
 * catch and convert into accumulated {@link io.tiko.config.internal.ConfigError}s.
 */
public interface TypeCoercer<T> {
    T coerce(Object yamlValue);
}
