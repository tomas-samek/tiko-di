package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MCP tool: groups components by (interface FQN, qualifier) and reports any group
 * whose entries pin to ≥2 distinct non-empty profile values — i.e. environment-specific
 * implementations of the same type that an agent setting up multi-env config needs to
 * know about.
 *
 * <p>Wildcards (components with empty {@code profiles[]}, active under all profiles)
 * are <strong>not</strong> conflict-creating on their own. A group with one wildcard
 * and one pinned impl (e.g. {@code [profiles:[], profiles:["dev"]]}) is the common
 * "default + dev-specific override" pattern, not a conflict. Only when ≥2 entries pin
 * to distinct non-empty profile values does the group surface as a conflict.
 */
public final class ListProfileConflictsTool {

    public static final String NAME = "list_profile_conflicts";

    private final TopologyStore store;

    public ListProfileConflictsTool(TopologyStore store) {
        this.store = store;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> args) {
        var groups = new TreeMap<String, List<Map<String, Object>>>();
        for (var c : store.components()) {
            var interfaces = (List<Object>) c.getOrDefault("interfaces", List.of());
            var qualifier = c.get("qualifier");
            for (var iface : interfaces) {
                var k = iface + "|" + (qualifier == null ? "" : qualifier);
                groups.computeIfAbsent(k, kk -> new ArrayList<>()).add(c);
            }
        }
        var conflicts = new ArrayList<Map<String, Object>>();
        for (var e : groups.entrySet()) {
            var impls = e.getValue();
            if (impls.size() < 2) continue;
            if (!hasDisjointProfiles(impls)) continue;
            var k = e.getKey().split("\\|", -1);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("type", k[0]);
            entry.put("qualifier", k[1].isEmpty() ? null : k[1]);
            var implsOut = new ArrayList<Map<String, Object>>();
            for (var c : impls) {
                var row = new LinkedHashMap<String, Object>();
                row.put("qualifiedName", c.get("qualifiedName"));
                row.put("profiles", c.getOrDefault("profiles", List.of()));
                implsOut.add(row);
            }
            entry.put("implementations", implsOut);
            conflicts.add(entry);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("conflicts", conflicts);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasDisjointProfiles(List<Map<String, Object>> impls) {
        // Treat empty profiles ("active under all profiles") as wildcard. A group is a conflict
        // iff at least two entries pin to distinct non-empty profile values.
        var seen = new HashSet<String>();
        for (var c : impls) {
            var profiles = (List<Object>) c.getOrDefault("profiles", List.of());
            if (profiles.isEmpty()) continue;
            for (var p : profiles) seen.add(p.toString());
        }
        return seen.size() >= 2;
    }
}
