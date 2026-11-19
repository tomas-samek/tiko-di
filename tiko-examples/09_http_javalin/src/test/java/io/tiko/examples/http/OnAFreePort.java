package io.tiko.examples.http;

import io.tiko.config.ConfigSources;
import io.tiko.runtime.TikoOptions;
import java.util.Map;

/** Options for a test container whose HTTP server binds a port the OS picks. */
final class OnAFreePort {

    private OnAFreePort() {}

    static TikoOptions.Builder options() {
        return TikoOptions.builder().configSource(ConfigSources.fromMap(Map.of("http", Map.of("port", 0))));
    }
}
