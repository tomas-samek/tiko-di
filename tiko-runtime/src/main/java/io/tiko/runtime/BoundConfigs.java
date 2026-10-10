package io.tiko.runtime;

import java.time.Duration;
import java.util.Map;

/**
 * What {@code Tiko.create} took from configuration binding (#114). Free of tiko-config types so
 * {@link Tiko} links without tiko-config on the classpath; {@link ConfigBinding} produces it.
 *
 * @param userConfigs the bound {@code @Configuration} records to inject into the container
 * @param shutdownTimeout the bound {@code tiko.shutdownTimeout}, or {@code null} without tiko-config
 */
record BoundConfigs(Map<Class<?>, Object> userConfigs, Duration shutdownTimeout) {

    /** No configuration binding ran: tiko-config is not on the classpath. */
    static final BoundConfigs NONE = new BoundConfigs(Map.of(), null);
}
