package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: targeted proxy detail for a single component. Returns the
 * {@code interface}, {@code proxiedMethods}, and {@code reason} for a bean the
 * processor decided to proxy. Returns {@code proxied:false} for components that
 * don't require a proxy, and a structured not-found response for unknown FQNs.
 *
 * <p>Per-partes-aligned per {@code docs/mcp-design.md}: the question is "tell me
 * about THIS bean", the response is bounded to a small fixed shape with only one
 * variable-length list ({@code proxiedMethods}) whose size is dictated by the
 * declared interface, not by codebase volume.
 *
 * <p>The companion processor change writes the {@code proxy} block into
 * {@code topology.json} only for proxied components, so most components carry no
 * extra footprint.
 */
public final class ExplainProxyTool {

    public static final String NAME = "explain_proxy";

    private static final String KEY_COMPONENT_FQN = "componentFqn";
    private static final String KEY_INTERFACE = "interface";
    private static final String KEY_PROXIED_METHODS = "proxiedMethods";
    private static final String KEY_REASON = "reason";

    private final TopologyStore store;

    public ExplainProxyTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var fqn = ToolArgs.required(args, KEY_COMPONENT_FQN);
        for (var c : store.components()) {
            if (!fqn.equals(c.get("qualifiedName"))) {
                continue;
            }
            return projectComponent(c);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put(KEY_COMPONENT_FQN, fqn);
        out.put("found", false);
        out.put(KEY_REASON, "Component not found in topology");
        return out;
    }

    private static Map<String, Object> projectComponent(Map<String, Object> c) {
        var out = new LinkedHashMap<String, Object>();
        out.put(KEY_COMPONENT_FQN, c.get("qualifiedName"));
        if (!Boolean.TRUE.equals(c.get("requiresProxy"))) {
            out.put("proxied", false);
            return out;
        }
        out.put("proxied", true);
        var proxy = c.get("proxy");
        if (proxy instanceof Map) {
            @SuppressWarnings("unchecked")
            var p = (Map<String, Object>) proxy;
            out.put(KEY_INTERFACE, p.get(KEY_INTERFACE));
            out.put(KEY_PROXIED_METHODS, p.getOrDefault(KEY_PROXIED_METHODS, List.of()));
            out.put(KEY_REASON, p.get(KEY_REASON));
        } else {
            // Older topology snapshots may have requiresProxy=true without the proxy
            // detail block. Surface that honestly rather than fabricating fields.
            out.put(KEY_INTERFACE, null);
            out.put(KEY_PROXIED_METHODS, List.of());
            out.put(KEY_REASON, null);
        }
        return out;
    }
}
