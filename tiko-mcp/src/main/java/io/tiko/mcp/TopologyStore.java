package io.tiko.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code META-INF/tiko/topology.json}, {@code META-INF/tiko/config-schema.json},
 * and {@code META-INF/tiko/wiring-errors.json} from every Maven module under the
 * given project root and merges them into a single in-memory model. Config schemas
 * are merged into a single {@code properties: {}} union keyed by prefix (later
 * modules win on collision). Wiring errors are appended across modules in the
 * order they are visited.
 */
public final class TopologyStore {

    private final Path projectRoot;
    private final List<Map<String, Object>> components;
    private final List<Map<String, Object>> factoryMethods;
    private final List<Map<String, Object>> eventHandlers;
    private final List<Map<String, Object>> eventTriggers;
    private final List<Map<String, Object>> configurations;
    private final List<Map<String, Object>> wiringErrors;
    private Map<String, Object> configSchema; // nullable
    private Instant loadedAt;

    private TopologyStore(
            Path projectRoot,
            List<Map<String, Object>> components,
            List<Map<String, Object>> factoryMethods,
            List<Map<String, Object>> eventHandlers,
            List<Map<String, Object>> eventTriggers,
            List<Map<String, Object>> configurations,
            List<Map<String, Object>> wiringErrors,
            Map<String, Object> configSchema,
            Instant loadedAt) {
        this.projectRoot = projectRoot;
        this.components = components;
        this.factoryMethods = factoryMethods;
        this.eventHandlers = eventHandlers;
        this.eventTriggers = eventTriggers;
        this.configurations = configurations;
        this.wiringErrors = wiringErrors;
        this.configSchema = configSchema;
        this.loadedAt = loadedAt;
    }

    public static TopologyStore loadFrom(Path projectRoot) {
        var components = new ArrayList<Map<String, Object>>();
        var factoryMethods = new ArrayList<Map<String, Object>>();
        var eventHandlers = new ArrayList<Map<String, Object>>();
        var eventTriggers = new ArrayList<Map<String, Object>>();
        var configurations = new ArrayList<Map<String, Object>>();
        var wiringErrors = new ArrayList<Map<String, Object>>();
        Map<String, Object> mergedSchema = null;

        for (var topologyFile : findFiles(projectRoot, "topology.json")) {
            var doc = readJsonObject(topologyFile);
            appendIfArray(doc, "components", components);
            appendIfArray(doc, "factoryMethods", factoryMethods);
            appendIfArray(doc, "eventHandlers", eventHandlers);
            appendIfArray(doc, "eventTriggers", eventTriggers);
            appendIfArray(doc, "configurations", configurations);
        }

        for (var wiringErrorsFile : findFiles(projectRoot, "wiring-errors.json")) {
            var doc = readJsonObject(wiringErrorsFile);
            appendIfArray(doc, "errors", wiringErrors);
        }

        for (var schemaFile : findFiles(projectRoot, "config-schema.json")) {
            var doc = readJsonObject(schemaFile);
            if (mergedSchema == null) {
                mergedSchema = new LinkedHashMap<>(doc);
                var props = mergedSchema.get("properties");
                if (props instanceof Map<?, ?> m) {
                    mergedSchema.put("properties", new LinkedHashMap<>(m));
                }
            } else {
                var props = doc.get("properties");
                var existing = mergedSchema.get("properties");
                if (props instanceof Map && existing instanceof Map) {
                    @SuppressWarnings("unchecked")
                    var mergedProps = (Map<String, Object>) existing;
                    @SuppressWarnings("unchecked")
                    var incoming = (Map<String, Object>) props;
                    mergedProps.putAll(incoming);
                }
            }
        }

        return new TopologyStore(
                projectRoot,
                components,
                factoryMethods,
                eventHandlers,
                eventTriggers,
                configurations,
                wiringErrors,
                mergedSchema,
                Instant.now());
    }

    public synchronized void reload() {
        var fresh = loadFrom(projectRoot);
        this.components.clear();
        this.components.addAll(fresh.components);
        this.factoryMethods.clear();
        this.factoryMethods.addAll(fresh.factoryMethods);
        this.eventHandlers.clear();
        this.eventHandlers.addAll(fresh.eventHandlers);
        this.eventTriggers.clear();
        this.eventTriggers.addAll(fresh.eventTriggers);
        this.configurations.clear();
        this.configurations.addAll(fresh.configurations);
        this.wiringErrors.clear();
        this.wiringErrors.addAll(fresh.wiringErrors);
        this.configSchema = fresh.configSchema;
        this.loadedAt = fresh.loadedAt;
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public List<Map<String, Object>> components() {
        return components;
    }

    public List<Map<String, Object>> factoryMethods() {
        return factoryMethods;
    }

    public List<Map<String, Object>> eventHandlers() {
        return eventHandlers;
    }

    public List<Map<String, Object>> eventTriggers() {
        return eventTriggers;
    }

    public List<Map<String, Object>> configurations() {
        return configurations;
    }

    public List<Map<String, Object>> wiringErrors() {
        return wiringErrors;
    }

    public Map<String, Object> configSchema() {
        return configSchema;
    }

    public List<String> configSchemaPrefixes() {
        if (configSchema == null) return List.of();
        var props = configSchema.get("properties");
        if (props instanceof Map<?, ?> m) {
            return m.keySet().stream().map(Object::toString).toList();
        }
        return List.of();
    }

    // ----- helpers -----

    private static List<Path> findFiles(Path root, String fileName) {
        var result = new ArrayList<Path>();
        if (!Files.isDirectory(root)) return result;
        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:**/target/classes/META-INF/tiko/" + fileName);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matcher.matches(file)) {
                        result.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonObject(Path path) {
        try {
            var src = Files.readString(path, StandardCharsets.UTF_8);
            var parsed = JsonReader.parse(src);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException("Expected JSON object in " + path);
            }
            return (Map<String, Object>) parsed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendIfArray(Map<String, Object> doc, String key, List<Map<String, Object>> dest) {
        var v = doc.get(key);
        if (v instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map) {
                    dest.add((Map<String, Object>) item);
                }
            }
        }
    }
}
