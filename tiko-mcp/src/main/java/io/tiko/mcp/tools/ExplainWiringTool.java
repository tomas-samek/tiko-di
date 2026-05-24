package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: BFS over a component's constructor-dependency edges, marking cycles
 * (re-visits), cross-scope proxy edges, and {@code @Configuration}-record leaves.
 *
 * <p>The walker descends through {@code @Component} dependencies and surfaces
 * {@code @Configuration} record deps as terminal tree entries with
 * {@code kind: "CONFIG"} so the full injection surface is visible in one call —
 * agents don't have to cross-reference with {@code get_config_schema} just to
 * discover that {@code OrderRepository} depends on {@code DbConfig}.
 */
public final class ExplainWiringTool {

    public static final String NAME = "explain_wiring";

    private static final long DEFAULT_MAX_DEPTH = 10L;

    private final TopologyStore store;

    public ExplainWiringTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var fqn = ToolArgs.required(args, "componentFqn");
        long maxDepth = args.get("maxDepth") instanceof Long l ? l : DEFAULT_MAX_DEPTH;
        var profile = ToolArgs.strOrNull(args.get("profile"));

        var root = findComponent(fqn, profile);
        if (root == null) {
            var matches = store.components().stream()
                    .map(c -> (String) c.get("qualifiedName"))
                    .filter(n -> n != null && n.contains(ToolArgs.simpleName(fqn)))
                    .toList();
            var msg = "Unknown component '" + fqn + "'.";
            if (!matches.isEmpty()) {
                msg += " Did you mean one of: " + matches + "?";
            }
            throw new IllegalArgumentException(msg);
        }

        var tree = new ArrayList<Map<String, Object>>();
        var queue = new ArrayDeque<Node>();
        var visited = new HashSet<String>();
        queue.add(new Node(fqn, 0L, null));

        while (!queue.isEmpty()) {
            var n = queue.poll();
            if (n.depth > maxDepth) continue;

            var component = findComponent(n.fqn, profile);
            if (component != null) {
                var isCycle = !visited.add(n.fqn);
                var entry = new LinkedHashMap<String, Object>();
                entry.put("depth", n.depth);
                entry.put("kind", "COMPONENT");
                entry.put("component", component);
                entry.put("via", n.via);
                entry.put("cycle", isCycle);
                entry.put("proxied", Boolean.TRUE.equals(component.get("requiresProxy")));
                tree.add(entry);

                if (isCycle) continue; // don't descend through cycles

                @SuppressWarnings("unchecked")
                var deps = (List<Map<String, Object>>) component.getOrDefault("constructorDependencies", List.of());
                for (var dep : deps) {
                    var depType = (String) dep.get("type");
                    if (depType != null) {
                        queue.add(new Node(depType, n.depth + 1, dep));
                    }
                }
                continue;
            }

            var factory = findFactory(n.fqn);
            if (factory != null) {
                var isCycle = !visited.add(n.fqn);
                var entry = new LinkedHashMap<String, Object>();
                entry.put("depth", n.depth);
                entry.put("kind", "PRODUCED");
                entry.put("component", FactoryProjection.fromFactory(factory));
                entry.put("via", n.via);
                entry.put("cycle", isCycle);
                entry.put("proxied", Boolean.TRUE.equals(factory.get("requiresProxy")));
                tree.add(entry);
                if (isCycle) continue;
                queue.add(new Node((String) factory.get("declaringClass"), n.depth + 1, null));
                @SuppressWarnings("unchecked")
                var deps = (List<Map<String, Object>>) factory.getOrDefault("constructorDependencies", List.of());
                for (var dep : deps) {
                    var depType = (String) dep.get("type");
                    if (depType != null) queue.add(new Node(depType, n.depth + 1, dep));
                }
                continue;
            }

            var configuration = findConfiguration(n.fqn);
            if (configuration != null) {
                var isCycle = !visited.add(n.fqn);
                var entry = new LinkedHashMap<String, Object>();
                entry.put("depth", n.depth);
                entry.put("kind", "CONFIG");
                entry.put("component", configuration);
                entry.put("via", n.via);
                entry.put("cycle", isCycle);
                entry.put("proxied", false);
                tree.add(entry);
                // Configurations are leaf records — no further deps to descend into.
            }
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("root", root);
        out.put("tree", tree);
        return out;
    }

    /**
     * Resolves a dependency type to its providing component. Prefers an exact
     * {@code qualifiedName} match (the concrete component case); falls back to
     * scanning {@code interfaces[]} so interface-typed deps walk through to the
     * implementing component — mirrors the runtime container's interface dispatch.
     * If multiple components implement the same interface, prefer the first
     * non-test one (matches {@code ProcessorContext.findComponentOrFactory}).
     */
    private Map<String, Object> findComponent(String fqn, String profile) {
        for (var c : store.components()) {
            if (fqn.equals(c.get("qualifiedName"))) {
                return c;
            }
        }
        Map<String, Object> testFallback = null;
        for (var c : store.components()) {
            @SuppressWarnings("unchecked")
            var interfaces = (List<String>) c.getOrDefault("interfaces", List.of());
            if (!interfaces.contains(fqn)) continue;
            if (profile != null && !ProfileSupport.profileMatches(c, profile)) continue;
            if (!Boolean.TRUE.equals(c.get("isTestComponent"))) {
                return c;
            }
            if (testFallback == null) testFallback = c;
        }
        return testFallback;
    }

    /**
     * Resolves a dependency type to a {@code @Produces} factory method by {@code returnType}.
     * Factory-produced beans don't appear in {@code components[]} — they live in
     * {@code factoryMethods[]} — so the walker checks here between the component and
     * configuration resolution steps.
     */
    private Map<String, Object> findFactory(String fqn) {
        for (var f : store.factoryMethods()) {
            if (fqn.equals(f.get("returnType"))) {
                return f;
            }
        }
        return null;
    }

    /**
     * Resolves a dependency type to a {@code @Configuration} record by qualified name.
     * Configurations don't appear in {@code components[]} — they live in a separate
     * topology section — so the walker checks here whenever {@link #findComponent}
     * returns null.
     */
    private Map<String, Object> findConfiguration(String fqn) {
        for (var cfg : store.configurations()) {
            if (fqn.equals(cfg.get("qualifiedName"))) {
                return cfg;
            }
        }
        return null;
    }

    private record Node(String fqn, long depth, Map<String, Object> via) {}
}
