package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: list every {@code @Component} in the loaded topology, optionally
 * filtered by {@code scope} and/or implemented {@code interface}.
 */
public final class ListComponentsTool {

    public static final String NAME = "list_components";

    private final TopologyStore store;

    public ListComponentsTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var scope = strOrNull(args.get("scope"));
        var iface = strOrNull(args.get("interface"));

        var filtered = store.components().stream()
                .filter(c -> scope == null || scope.equals(c.get("scope")))
                .filter(c -> iface == null || interfacesContain(c, iface))
                .toList();

        var out = new LinkedHashMap<String, Object>();
        out.put("components", filtered);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static boolean interfacesContain(Map<String, Object> component, String fqn) {
        var v = component.get("interfaces");
        if (!(v instanceof List)) return false;
        return ((List<Object>) v).stream().anyMatch(fqn::equals);
    }

    private static String strOrNull(Object v) {
        if (v == null) return null;
        var s = v.toString();
        return s.isEmpty() ? null : s;
    }
}
