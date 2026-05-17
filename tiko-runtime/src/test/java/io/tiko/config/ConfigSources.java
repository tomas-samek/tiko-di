package io.tiko.config;

import io.tiko.ConfigSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-classpath stub of {@code io.tiko.config.ConfigSources}.
 *
 * <p>The runtime reflectively looks up this FQN to keep tiko-runtime free of a compile dep
 * on tiko-config. The real implementation lives in the tiko-config module, but a reactor
 * cycle prevents adding it as a test-scoped dep (tiko-config already depends on
 * tiko-runtime for test). These stubs provide just enough behaviour for
 * {@code TikoResolveShutdownTimeoutTest} to exercise the reflective YAML path.
 *
 * <p>Implements:
 * <ul>
 *   <li>{@code classpathAll(String)} — returns an empty source (no test resources are
 *       shipped at {@code META-INF/tiko/defaults.yaml} for this test).</li>
 *   <li>{@code layered(ConfigSource[])} — shallow merge, later sources win on top-level keys
 *       (sufficient for {@code tiko.shutdownTimeout} lookups).</li>
 * </ul>
 */
public final class ConfigSources {

    private ConfigSources() {}

    public static ConfigSource classpathAll(String resource) {
        return Map::of;
    }

    public static ConfigSource layered(ConfigSource... sources) {
        return () -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (ConfigSource s : sources) {
                Map<String, Object> loaded = s.load();
                for (Map.Entry<String, Object> e : loaded.entrySet()) {
                    merged.merge(e.getKey(), e.getValue(), ConfigSources::deepMerge);
                }
            }
            return merged;
        };
    }

    @SuppressWarnings("unchecked")
    private static Object deepMerge(Object existing, Object incoming) {
        if (existing instanceof Map<?, ?> em && incoming instanceof Map<?, ?> im) {
            Map<String, Object> out = new HashMap<>((Map<String, Object>) em);
            for (Map.Entry<?, ?> e : im.entrySet()) {
                out.merge((String) e.getKey(), e.getValue(), ConfigSources::deepMerge);
            }
            return out;
        }
        if (existing instanceof List<?> el && incoming instanceof List<?> il) {
            return il; // overlay wins for lists, matching tiko-config semantics
        }
        return incoming;
    }
}
