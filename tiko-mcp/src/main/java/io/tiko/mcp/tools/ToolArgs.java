package io.tiko.mcp.tools;

import java.util.Map;

/**
 * Small static helpers shared by the MCP tool classes for argument parsing and
 * FQN handling. Package-private — these are implementation details, not part of
 * the tool API surface. Extracted in #182 after the same three helpers landed
 * inline in four different tool files during the #140-#145 batch.
 */
final class ToolArgs {

    private ToolArgs() {}

    /**
     * Returns {@code args.get(key).toString()}, throwing {@code IllegalArgumentException}
     * when the value is missing or empty. Used by tools whose JSON schema marks an
     * argument as {@code required}.
     */
    static String required(Map<String, Object> args, String key) {
        var v = args.get(key);
        if (v == null || v.toString().isEmpty()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return v.toString();
    }

    /**
     * Returns the substring after the last {@code '.'} in an FQN, or the input
     * unchanged when no dot is present. Used by did-you-mean suggestion logic.
     */
    static String simpleName(String fqn) {
        var dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /**
     * Returns {@code v.toString()} or {@code null} when {@code v} is null or its
     * string form is empty. Used by tools that accept an optional string filter
     * argument and want to treat empty/missing as "no filter".
     */
    static String strOrNull(Object v) {
        if (v == null) return null;
        var s = v.toString();
        return s.isEmpty() ? null : s;
    }
}
