package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayList;
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

        var components = new ArrayList<Map<String, Object>>();
        for (var c : store.components()) {
            if (scope != null && !scope.equals(c.get("scope"))) continue;
            if (iface != null && !interfacesContain(c, iface)) continue;
            components.add(withKind(c, "COMPONENT"));
        }
        for (var f : store.factoryMethods()) {
            if (scope != null && !scope.equals(f.get("scope"))) continue;
            // Produced entries don't carry an `interfaces[]` array; filter by returnType only.
            // Idiomatic @Produces returns the interface directly (e.g. `@Produces public DataSource ...`),
            // so this matches the common case. Producers returning a concrete impl of an interface
            // won't be surfaced under the interface filter — out of scope for #143.
            if (iface != null && !iface.equals(f.get("returnType"))) continue;
            components.add(FactoryProjection.fromFactory(f));
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("components", components);
        return out;
    }

    private static Map<String, Object> withKind(Map<String, Object> c, String kind) {
        var out = new LinkedHashMap<String, Object>(c);
        out.putIfAbsent("kind", kind);
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
