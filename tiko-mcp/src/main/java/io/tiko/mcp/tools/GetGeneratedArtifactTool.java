package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * MCP tool: returns the on-disk location and a tiny structured summary of a generated
 * source file. Does <strong>not</strong> return file contents — the agent reads bytes
 * via its filesystem tool if it actually needs them (see {@code docs/mcp-design.md},
 * corollary 3).
 *
 * <p>Five kinds, all under {@code target/generated-sources/annotations/io/tiko/generated/}:
 * <ul>
 *   <li>{@code FACTORY} — {@code <SimpleName>Factory.java}, requires {@code componentFqn}
 *   <li>{@code PROXY} — {@code <SimpleName>Proxy.java}, requires {@code componentFqn};
 *       returns {@code exists:false, reason:"<X> is not proxied"} for non-proxied beans
 *   <li>{@code CONFIG_BINDER} — {@code <ConfigSimpleName>Binder.java} under {@code config/},
 *       requires {@code componentFqn} (the {@code @Configuration} record's FQN)
 *   <li>{@code CONTAINER} — {@code TikoContainerImpl_<hash>.java}, project-singular per module
 *   <li>{@code EVENT_REGISTRY} — {@code EventRegistry_<hash>.java}, project-singular per module
 * </ul>
 */
public final class GetGeneratedArtifactTool {

    public static final String NAME = "get_generated_artifact";

    static final String KIND_FACTORY = "FACTORY";
    static final String KIND_PROXY = "PROXY";
    static final String KIND_CONTAINER = "CONTAINER";
    static final String KIND_EVENT_REGISTRY = "EVENT_REGISTRY";
    static final String KIND_CONFIG_BINDER = "CONFIG_BINDER";

    private static final String KEY_COMPONENT_FQN = "componentFqn";
    private static final String KEY_KIND = "kind";
    private static final String KEY_EXISTS = "exists";
    private static final String KEY_PATH = "path";
    private static final String KEY_REASON = "reason";

    private final TopologyStore store;

    public GetGeneratedArtifactTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var kind = ToolArgs.required(args, KEY_KIND);
        var componentFqn = ToolArgs.strOrNull(args.get(KEY_COMPONENT_FQN));

        return switch (kind) {
            case KIND_FACTORY ->
                resolveComponentArtifact(kind, componentFqn, simpleName -> simpleName + "Factory.java", null);
            case KIND_PROXY -> resolveProxy(componentFqn);
            case KIND_CONFIG_BINDER -> resolveConfigBinder(componentFqn);
            case KIND_CONTAINER -> resolveSingular(kind, "TikoContainerImpl_");
            case KIND_EVENT_REGISTRY -> resolveSingular(kind, "EventRegistry_");
            default ->
                throw new IllegalArgumentException("Invalid kind: " + kind
                        + " (expected one of FACTORY, PROXY, CONTAINER, EVENT_REGISTRY, CONFIG_BINDER)");
        };
    }

    // === Component-keyed kinds =========================================================

    private Map<String, Object> resolveComponentArtifact(
            String kind,
            String componentFqn,
            java.util.function.Function<String, String> filenameFor,
            String skipReason) {
        if (componentFqn == null) {
            throw new IllegalArgumentException(KEY_COMPONENT_FQN + " is required for kind " + kind);
        }
        var component = findComponent(componentFqn);
        if (component.isEmpty()) {
            return notFound(kind, componentFqn, "Component not found in topology");
        }
        if (skipReason != null) {
            return notFound(kind, componentFqn, skipReason);
        }
        var simpleName = simpleName(componentFqn);
        return locate(kind, filenameFor.apply(simpleName), "io/tiko/generated", componentFqn);
    }

    private Map<String, Object> resolveProxy(String componentFqn) {
        if (componentFqn == null) {
            throw new IllegalArgumentException(KEY_COMPONENT_FQN + " is required for kind " + KIND_PROXY);
        }
        var component = findComponent(componentFqn);
        if (component.isEmpty()) {
            return notFound(KIND_PROXY, componentFqn, "Component not found in topology");
        }
        if (!Boolean.TRUE.equals(component.orElseThrow().get("requiresProxy"))) {
            return notFound(KIND_PROXY, componentFqn, componentFqn + " is not proxied");
        }
        return locate(KIND_PROXY, simpleName(componentFqn) + "Proxy.java", "io/tiko/generated", componentFqn);
    }

    private Map<String, Object> resolveConfigBinder(String componentFqn) {
        if (componentFqn == null) {
            throw new IllegalArgumentException(KEY_COMPONENT_FQN + " is required for kind " + KIND_CONFIG_BINDER);
        }
        var present = store.configurations().stream().anyMatch(c -> componentFqn.equals(c.get("qualifiedName")));
        if (!present) {
            return notFound(KIND_CONFIG_BINDER, componentFqn, "Configuration not found in topology");
        }
        return locate(
                KIND_CONFIG_BINDER, simpleName(componentFqn) + "Binder.java", "io/tiko/generated/config", componentFqn);
    }

    // === Singular kinds =================================================================

    private Map<String, Object> resolveSingular(String kind, String filenamePrefix) {
        // Walk the project root for a file matching <prefix>*.java under any
        // generated-sources/annotations/io/tiko/generated/ directory. Returns the first
        // match found; multi-module reactors with multiple containers surface only one
        // (acceptable limitation — the agent can ask again with componentFqn-keyed kinds
        // to drill into a specific module's components).
        try (Stream<Path> walk = Files.walk(store.projectRoot(), FileVisitOption.FOLLOW_LINKS)) {
            var match = walk.filter(p -> {
                        var name = p.getFileName().toString();
                        if (!name.startsWith(filenamePrefix) || !name.endsWith(".java")) {
                            return false;
                        }
                        var pathStr = p.toString().replace('\\', '/');
                        return pathStr.contains("/generated-sources/annotations/io/tiko/generated/");
                    })
                    .findFirst();
            if (match.isEmpty()) {
                return notFound(
                        kind,
                        null,
                        "No " + filenamePrefix + "*.java found on disk under generated-sources — run mvn compile?");
            }
            return summarise(kind, null, match.orElseThrow());
        } catch (IOException e) {
            throw new IllegalStateException("Filesystem walk failed: " + e.getMessage(), e);
        }
    }

    // === Filesystem helpers =============================================================

    private Map<String, Object> locate(String kind, String fileName, String pkgDir, String componentFqn) {
        var expectedTail = "/generated-sources/annotations/" + pkgDir + "/" + fileName;
        try (Stream<Path> walk = Files.walk(store.projectRoot(), FileVisitOption.FOLLOW_LINKS)) {
            var match = walk.filter(p -> {
                        if (!p.getFileName().toString().equals(fileName)) {
                            return false;
                        }
                        return p.toString().replace('\\', '/').endsWith(expectedTail);
                    })
                    .findFirst();
            if (match.isEmpty()) {
                return notFound(
                        kind,
                        componentFqn,
                        "Generated file " + fileName
                                + " not found on disk — topology snapshot may predate sources; run mvn compile");
            }
            return summarise(kind, componentFqn, match.orElseThrow());
        } catch (IOException e) {
            throw new IllegalStateException("Filesystem walk failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> summarise(String kind, String componentFqn, Path file) {
        var out = new LinkedHashMap<String, Object>();
        out.put(KEY_PATH, file.toString().replace('\\', '/'));
        out.put(KEY_KIND, kind);
        if (componentFqn != null) {
            out.put(KEY_COMPONENT_FQN, componentFqn);
        }
        out.put(KEY_EXISTS, true);
        try {
            out.put("lines", countLines(file));
            out.put("lastModifiedEpochMs", Files.getLastModifiedTime(file).toMillis());
        } catch (IOException e) {
            // Race between the walk and the stat — surface as a soft failure rather than throwing.
            out.put("lines", -1);
            out.put("lastModifiedEpochMs", -1L);
        }
        return out;
    }

    private static long countLines(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.count();
        }
    }

    // === Topology lookups ===============================================================

    private Optional<Map<String, Object>> findComponent(String fqn) {
        for (var c : store.components()) {
            if (fqn.equals(c.get("qualifiedName"))) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    // === Response builders ==============================================================

    private static Map<String, Object> notFound(String kind, String componentFqn, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put(KEY_KIND, kind);
        if (componentFqn != null) {
            out.put(KEY_COMPONENT_FQN, componentFqn);
        }
        out.put(KEY_EXISTS, false);
        out.put(KEY_REASON, reason);
        return out;
    }

    private static String simpleName(String fqn) {
        var dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
