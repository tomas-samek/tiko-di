package io.tiko.config.internal.coercers;

/**
 * Test-classpath stub of the tiko-config {@code TypeCoercer} interface. See
 * {@link io.tiko.config.ConfigSources} for the rationale (reactor cycle blocks a
 * test-scoped dep on tiko-config).
 */
public interface TypeCoercer<T> {
    T coerce(Object value);
}
