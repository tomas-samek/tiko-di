package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: returns the union JSON Schema for every {@code @Configuration} record
 * discovered in the project. With a {@code prefix} argument, returns just the
 * slice at {@code properties.<prefix>}.
 */
public final class GetConfigSchemaTool {

    public static final String NAME = "get_config_schema";

    private final TopologyStore store;

    public GetConfigSchemaTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        Object prefixObj = args.get("prefix");
        String prefix = prefixObj == null ? null : prefixObj.toString();

        Map<String, Object> schema = store.configSchema();
        var out = new LinkedHashMap<String, Object>();

        if (prefix == null || prefix.isEmpty()) {
            out.put("schema", schema);
            return out;
        }

        if (schema == null) {
            throw new IllegalArgumentException("No config schema is present in this project");
        }

        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> propsMap) || !propsMap.containsKey(prefix)) {
            throw new IllegalArgumentException(
                    "Unknown config prefix '" + prefix + "'. Known: " + store.configSchemaPrefixes());
        }

        out.put("schema", propsMap.get(prefix));
        return out;
    }
}
