package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: reverse-index lookup. Given {@code componentFqn}, returns the list of
 * components whose {@code constructorDependencies} reference it AND the host components
 * of any {@code @Produces} factory method whose parameters reference it (#183). With
 * {@code transitive: true}, walks the reverse graph with a visited set to bound cycles.
 *
 * <p>Configurations are valid lookup targets — they appear as deps in
 * {@code constructorDependencies}, same as components. Factory-host entries report the
 * {@code declaringClass} FQN (not {@code declaringClass#methodName}) so the result list
 * is uniformly component-FQN-shaped; pinpoint which method via {@code list_components}
 * (look at the host's {@code PRODUCED} entries) or {@code explain_wiring}.
 */
public final class FindDependentsTool {

    public static final String NAME = "find_dependents";

    private final TopologyStore store;

    public FindDependentsTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var target = required(args, "componentFqn");
        var transitive = Boolean.TRUE.equals(args.get("transitive"));

        if (!isKnown(target)) {
            var matches = candidateMatches(target);
            var msg = "Unknown component '" + target + "'.";
            if (!matches.isEmpty()) msg += " Did you mean one of: " + matches + "?";
            throw new IllegalArgumentException(msg);
        }

        var direct = directDependents(target);
        var dependents = transitive ? walkReverse(direct) : direct;

        var out = new LinkedHashMap<String, Object>();
        out.put("dependents", new ArrayList<>(dependents));
        return out;
    }

    private boolean isKnown(String fqn) {
        for (var c : store.components()) if (fqn.equals(c.get("qualifiedName"))) return true;
        for (var cfg : store.configurations()) if (fqn.equals(cfg.get("qualifiedName"))) return true;
        return false;
    }

    private List<String> candidateMatches(String fqn) {
        var simple = simpleName(fqn);
        var result = new ArrayList<String>();
        for (var c : store.components()) {
            var n = (String) c.get("qualifiedName");
            if (n != null && n.contains(simple)) result.add(n);
        }
        for (var cfg : store.configurations()) {
            var n = (String) cfg.get("qualifiedName");
            if (n != null && n.contains(simple)) result.add(n);
        }
        return result;
    }

    private List<String> directDependents(String target) {
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var c : store.components()) {
            @SuppressWarnings("unchecked")
            var deps = (List<Map<String, Object>>) c.getOrDefault("constructorDependencies", List.of());
            for (var d : deps) {
                if (target.equals(d.get("type"))) {
                    var name = (String) c.get("qualifiedName");
                    if (name != null && seen.add(name)) result.add(name);
                    break;
                }
            }
        }
        for (var f : store.factoryMethods()) {
            @SuppressWarnings("unchecked")
            var deps = (List<Map<String, Object>>) f.getOrDefault("constructorDependencies", List.of());
            for (var d : deps) {
                if (target.equals(d.get("type"))) {
                    var declaringClass = (String) f.get("declaringClass");
                    if (declaringClass != null && seen.add(declaringClass)) result.add(declaringClass);
                    break;
                }
            }
        }
        return result;
    }

    private List<String> walkReverse(List<String> seeds) {
        var visited = new HashSet<>(seeds);
        var queue = new ArrayDeque<>(seeds);
        var result = new ArrayList<>(seeds);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var d : directDependents(current)) {
                if (visited.add(d)) {
                    result.add(d);
                    queue.add(d);
                }
            }
        }
        return result;
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
}
