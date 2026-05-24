package io.tiko.mcp;

import java.util.Map;
import java.util.function.Function;

/**
 * Declarative registration record for one MCP tool. Bundles the four things the
 * {@link McpStdioBridge} needs to expose a tool over JSON-RPC: its name (the
 * agent-facing identifier), a human description (shown to the agent in tool
 * listings), the JSON-Schema string for its input arguments, and the handler
 * that turns parsed args into a response payload.
 *
 * <p>{@link TikoMcpServer} builds a {@code List<ToolRegistration>} and passes it
 * to {@code McpStdioBridge}; the bridge no longer hard-codes which tools exist.
 * Added in #184 — the bridge had grown a 9-arg constructor and 9 schema text
 * blocks inline.
 */
public record ToolRegistration(
        String name,
        String description,
        String schemaJson,
        Function<Map<String, Object>, Map<String, Object>> handler) {}
