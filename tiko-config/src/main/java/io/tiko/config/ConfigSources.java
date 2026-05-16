package io.tiko.config;

import io.tiko.ConfigSource;
import io.tiko.SourceLocation;
import io.tiko.config.internal.DeepMerge;
import io.tiko.config.internal.YamlLoader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bundled {@link ConfigSource} factories. */
public final class ConfigSources {

    private ConfigSources() {}

    /** YAML resource loaded via the thread context classloader. */
    public static ConfigSource classpath(String resourcePath) {
        return new ConfigSource() {
            private YamlLoader.LoadedYaml loaded;

            private YamlLoader.LoadedYaml ensureLoaded() {
                if (loaded == null) {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    if (cl == null) cl = ConfigSources.class.getClassLoader();
                    try (InputStream in = cl.getResourceAsStream(resourcePath)) {
                        if (in == null) {
                            throw new RuntimeException("classpath resource not found: " + resourcePath);
                        }
                        loaded = YamlLoader.load(in, resourcePath);
                    } catch (IOException e) {
                        throw new RuntimeException("failed to read " + resourcePath, e);
                    }
                }
                return loaded;
            }

            @Override
            public Map<String, Object> load() {
                return ensureLoaded().data();
            }

            @Override
            public Map<String, SourceLocation> locations() {
                return ensureLoaded().locations();
            }
        };
    }

    /**
     * Discovers <em>every</em> occurrence of {@code resourcePath} on the classloader
     * (one per jar that ships it) and deep-merges them into a single
     * {@link ConfigSource}. Returns an empty source when nothing is found.
     *
     * <p>Used for module-baked defaults: each module ships
     * {@code META-INF/tiko/defaults.yaml} inside its own jar, and at runtime they are
     * pooled into one logical defaults tree. Layer with
     * {@link #layered(ConfigSource...)} to put a user override on top.</p>
     *
     * <p>Merge order is whatever the classloader returns. Modules SHOULD use
     * distinct top-level prefixes; cross-module prefix collisions surface in
     * {@code ConfigBootstrap}'s validator.</p>
     */
    public static ConfigSource classpathAll(String resourcePath) {
        return new ConfigSource() {
            private Map<String, Object> mergedData;
            private Map<String, SourceLocation> mergedLocations;

            private void ensureLoaded() {
                if (mergedData != null) return;
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = ConfigSources.class.getClassLoader();
                try {
                    Enumeration<URL> urls = cl.getResources(resourcePath);
                    Map<String, Object> data = new LinkedHashMap<>();
                    Map<String, SourceLocation> locations = new LinkedHashMap<>();
                    while (urls.hasMoreElements()) {
                        URL url = urls.nextElement();
                        try (InputStream in = url.openStream()) {
                            YamlLoader.LoadedYaml next = YamlLoader.load(in, url.toString());
                            data = DeepMerge.merge(data, next.data());
                            locations.putAll(next.locations()); // last-jar-wins per dot-path
                        }
                    }
                    mergedData = data;
                    mergedLocations = locations;
                } catch (IOException e) {
                    throw new RuntimeException("failed to enumerate " + resourcePath, e);
                }
            }

            @Override
            public Map<String, Object> load() {
                ensureLoaded();
                return mergedData;
            }

            @Override
            public Map<String, SourceLocation> locations() {
                ensureLoaded();
                return mergedLocations;
            }
        };
    }

    /** YAML file from the filesystem. */
    public static ConfigSource file(Path path) {
        return new ConfigSource() {
            private YamlLoader.LoadedYaml loaded;

            private YamlLoader.LoadedYaml ensureLoaded() {
                if (loaded == null) {
                    try (InputStream in = Files.newInputStream(path)) {
                        loaded = YamlLoader.load(in, path.toString());
                    } catch (IOException e) {
                        throw new RuntimeException("failed to read " + path, e);
                    }
                }
                return loaded;
            }

            @Override
            public Map<String, Object> load() {
                return ensureLoaded().data();
            }

            @Override
            public Map<String, SourceLocation> locations() {
                return ensureLoaded().locations();
            }
        };
    }

    /** In-memory source — invaluable for tests. */
    public static ConfigSource fromMap(Map<String, Object> data) {
        Map<String, Object> snapshot = new LinkedHashMap<>(data);
        return () -> snapshot;
    }

    /**
     * Layered source. Each subsequent source overrides earlier ones using
     * deep-merge (maps merge recursively, lists replace atomically, scalars overwrite).
     */
    public static ConfigSource layered(ConfigSource... sources) {
        if (sources.length == 0) throw new IllegalArgumentException("layered requires at least one source");
        return new ConfigSource() {
            @Override
            public Map<String, Object> load() {
                Map<String, Object> result = sources[0].load();
                for (int i = 1; i < sources.length; i++) {
                    result = DeepMerge.merge(result, sources[i].load());
                }
                return result;
            }

            @Override
            public Map<String, SourceLocation> locations() {
                Map<String, SourceLocation> merged = new LinkedHashMap<>();
                for (ConfigSource s : sources) {
                    merged.putAll(s.locations()); // last-source-wins
                }
                return merged;
            }
        };
    }
}
