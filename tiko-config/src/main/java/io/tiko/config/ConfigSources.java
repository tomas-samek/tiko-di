package io.tiko.config;

import io.tiko.ConfigSource;
import io.tiko.config.internal.DeepMerge;
import io.tiko.config.internal.YamlLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bundled {@link ConfigSource} factories. */
public final class ConfigSources {

    private ConfigSources() {}

    /** YAML resource loaded via the thread context classloader. */
    public static ConfigSource classpath(String resourcePath) {
        return () -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ConfigSources.class.getClassLoader();
            try (InputStream in = cl.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new RuntimeException("classpath resource not found: " + resourcePath);
                }
                return YamlLoader.load(in);
            } catch (IOException e) {
                throw new RuntimeException("failed to read " + resourcePath, e);
            }
        };
    }

    /** YAML file from the filesystem. */
    public static ConfigSource file(Path path) {
        return () -> {
            try (InputStream in = Files.newInputStream(path)) {
                return YamlLoader.load(in);
            } catch (IOException e) {
                throw new RuntimeException("failed to read " + path, e);
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
        return () -> {
            Map<String, Object> result = sources[0].load();
            for (int i = 1; i < sources.length; i++) {
                result = DeepMerge.merge(result, sources[i].load());
            }
            return result;
        };
    }
}
