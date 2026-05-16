package io.tiko;

import java.util.Map;

/**
 * Source of YAML-shaped configuration data. Implementations return a tree
 * of {@code Map<String,Object>}, {@code List<Object>}, and scalars
 * (Strings, Numbers, Booleans, etc.) — the same shape SnakeYAML produces
 * from a YAML document.
 *
 * <p>The container calls {@link #load()} once at startup. Implementations
 * should be deterministic and side-effect free.</p>
 *
 * <p>The returned tree is owned by the caller — the framework may mutate it
 * (for example, to perform {@code ${VAR}} interpolation in scalar values).
 * Implementations should return a fresh map or a defensive copy rather than
 * a shared cached instance.</p>
 *
 * <p>Bundled implementations are available via {@code io.tiko.config.ConfigSources}:
 * {@code classpath(String)}, {@code file(Path)}, {@code fromMap(Map)},
 * {@code layered(ConfigSource...)}.</p>
 */
@FunctionalInterface
public interface ConfigSource {
    /**
     * Returns the parsed configuration tree. Called once at container startup.
     * Never returns {@code null}; an empty source must return an empty map.
     *
     * @return the configuration tree, never {@code null}
     * @throws RuntimeException if the source cannot be loaded (file not found,
     *         malformed YAML, etc.)
     */
    Map<String, Object> load();

    /**
     * Returns a best-effort map of dot-path → source location for the
     * values produced by {@link #load()}. Returns an empty map by
     * default; YAML-backed sources override to expose Marks. Consumers
     * (typically the framework's binding pipeline) treat the absence
     * of an entry as "no location known" — they do not interpret an
     * empty return as "everything is at the origin".
     *
     * <p>Keys are dot-paths matching the YAML structure: top-level keys
     * like {@code "db"} and nested-record keys like {@code "app.server.host"}
     * appear as their fully-qualified path strings. Intermediate sections
     * also appear in the index (so binding can anchor a missing-key error
     * to the section header).
     */
    default Map<String, SourceLocation> locations() {
        return Map.of();
    }
}
