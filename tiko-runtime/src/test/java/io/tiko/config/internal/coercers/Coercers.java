package io.tiko.config.internal.coercers;

import java.time.Duration;

/**
 * Test-classpath stub of {@code io.tiko.config.internal.coercers.Coercers}. Only the
 * {@code durationCoercer()} factory needed by {@code Tiko.resolveShutdownTimeout} is
 * provided. See {@link io.tiko.config.ConfigSources} for the rationale.
 *
 * <p>Mirrors the real coercer's behaviour: parses ISO-8601 strings via
 * {@link Duration#parse(CharSequence)} and surfaces a {@link RuntimeException} on
 * failure so the runtime's {@code InvocationTargetException} unwrap path is exercised.
 */
public final class Coercers {

    private Coercers() {}

    public static TypeCoercer<Duration> durationCoercer() {
        return value -> {
            if (value instanceof Duration d) return d;
            String s = value == null ? null : value.toString();
            try {
                return Duration.parse(s);
            } catch (RuntimeException e) {
                throw new RuntimeException("expected duration, got \"" + s + "\"", e);
            }
        };
    }
}
