package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: returns processor diagnostics persisted to
 * {@code META-INF/tiko/wiring-errors.json}.
 *
 * <p>Each entry mirrors the on-disk shape:
 * {@code kind}, {@code sourceFile}, {@code line}, {@code componentFqn},
 * {@code message}, {@code suggestedFix}. See {@code docs/topology-schema.md}
 * for the field semantics.
 */
public final class ListWiringErrorsTool {

    public static final String NAME = "list_wiring_errors";

    private final TopologyStore store;

    public ListWiringErrorsTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var out = new LinkedHashMap<String, Object>();
        out.put("errors", store.wiringErrors());
        return out;
    }
}
