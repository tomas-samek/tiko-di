package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: re-reads META-INF/tiko/*.json from disk into the in-memory store.
 *
 * <p>Today the topology is loaded once at server boot. An agent that edits source,
 * runs {@code mvn compile}, and re-queries gets stale answers until the server
 * restarts. Calling {@code reload} after a rebuild refreshes the store in place.
 * Takes no arguments.
 */
public final class ReloadTool {

    public static final String NAME = "reload";

    private final TopologyStore store;

    public ReloadTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        store.reload();
        var out = new LinkedHashMap<String, Object>();
        out.put("reloaded", Boolean.TRUE);
        out.put("topologyTimestamp", store.loadedAt().toString());
        return out;
    }
}
