package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: denormalised cross-cut of {@code @PostConstruct} / {@code @PreDestroy}
 * methods across every component in the loaded topology. Answers the agent's
 * question "what runs at boot / shutdown?" without forcing it to fetch every
 * component to inspect their {@code lifecycle.*} arrays.
 *
 * <p>Per-partes-aligned per {@code docs/mcp-design.md}: each result entry is
 * three small string fields ({@code componentFqn}, {@code method}, {@code phase}),
 * and the natural data volume is low (lifecycle hooks are sparse even in large
 * codebases).
 *
 * <p>Optional {@code phase} filter narrows to one of {@code "POST_CONSTRUCT"} or
 * {@code "PRE_DESTROY"}. The result is sorted by {@code componentFqn}, then by
 * {@code phase} ({@code POST_CONSTRUCT} before {@code PRE_DESTROY}), for stable
 * ordering across builds.
 */
public final class ListLifecycleHooksTool {

    public static final String NAME = "list_lifecycle_hooks";

    private static final String PHASE_POST_CONSTRUCT = "POST_CONSTRUCT";
    private static final String PHASE_PRE_DESTROY = "PRE_DESTROY";

    private final TopologyStore store;

    public ListLifecycleHooksTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var phaseFilter = ToolArgs.strOrNull(args.get("phase"));
        if (phaseFilter != null
                && !PHASE_POST_CONSTRUCT.equals(phaseFilter)
                && !PHASE_PRE_DESTROY.equals(phaseFilter)) {
            throw new IllegalArgumentException(
                    "Invalid phase: " + phaseFilter + " (expected POST_CONSTRUCT or PRE_DESTROY)");
        }

        var hooks = new ArrayList<Map<String, Object>>();
        for (var c : store.components()) {
            var fqn = strOrEmpty(c.get("qualifiedName"));
            var lifecycle = asMap(c.get("lifecycle"));
            if (phaseFilter == null || PHASE_POST_CONSTRUCT.equals(phaseFilter)) {
                for (var method : methodNames(lifecycle.get("postConstruct"))) {
                    hooks.add(entry(fqn, method, PHASE_POST_CONSTRUCT));
                }
            }
            if (phaseFilter == null || PHASE_PRE_DESTROY.equals(phaseFilter)) {
                for (var method : methodNames(lifecycle.get("preDestroy"))) {
                    hooks.add(entry(fqn, method, PHASE_PRE_DESTROY));
                }
            }
        }

        // Stable order: by componentFqn, then by phase (POST_CONSTRUCT before PRE_DESTROY).
        hooks.sort(Comparator.<Map<String, Object>, String>comparing(h -> (String) h.get("componentFqn"))
                .thenComparing(h -> phaseOrder((String) h.get("phase"))));

        var out = new LinkedHashMap<String, Object>();
        out.put("hooks", hooks);
        return out;
    }

    private static Map<String, Object> entry(String componentFqn, String method, String phase) {
        var e = new LinkedHashMap<String, Object>();
        e.put("componentFqn", componentFqn);
        e.put("method", method);
        e.put("phase", phase);
        return e;
    }

    private static int phaseOrder(String phase) {
        return PHASE_POST_CONSTRUCT.equals(phase) ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> methodNames(Object v) {
        return v instanceof List ? (List<String>) v : List.of();
    }

    private static String strOrEmpty(Object v) {
        return v == null ? "" : v.toString();
    }
}
