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
 * (re-visits) and cross-scope proxy edges.
 */
public final class ExplainWiringTool {

    public static final String NAME = "explain_wiring";

    private static final long DEFAULT_MAX_DEPTH = 10L;

    private final TopologyStore store;

    public ExplainWiringTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var fqn = required(args, "componentFqn");
        long maxDepth = args.get("maxDepth") instanceof Long l ? l : DEFAULT_MAX_DEPTH;

        var root = findComponent(fqn);
        if (root == null) {
            var matches = store.components().stream()
                    .map(c -> (String) c.get("qualifiedName"))
                    .filter(n -> n != null && n.contains(simpleName(fqn)))
                    .toList();
            throw new IllegalArgumentException(
                    "Unknown component '" + fqn + "'. Did you mean one of: " + matches + "?");
        }

        var tree = new ArrayList<Map<String, Object>>();
        var queue = new ArrayDeque<Node>();
        var visited = new HashSet<String>();
        queue.add(new Node(fqn, 0L, null));

        while (!queue.isEmpty()) {
            var n = queue.poll();
            if (n.depth > maxDepth) continue;

            var component = findComponent(n.fqn);
            if (component == null) continue;

            var entry = new LinkedHashMap<String, Object>();
            entry.put("depth", n.depth);
            entry.put("component", component);
            entry.put("via", n.via);
            var isCycle = !visited.add(n.fqn);
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
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("root", root);
        out.put("tree", tree);
        return out;
    }

    private Map<String, Object> findComponent(String fqn) {
        return store.components().stream()
                .filter(c -> fqn.equals(c.get("qualifiedName")))
                .findFirst()
                .orElse(null);
    }

    private static String simpleName(String fqn) {
        var dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String required(Map<String, Object> args, String key) {
        var v = args.get(key);
        if (v == null || v.toString().isEmpty()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return v.toString();
    }

    private record Node(String fqn, long depth, Map<String, Object> via) {}
}
