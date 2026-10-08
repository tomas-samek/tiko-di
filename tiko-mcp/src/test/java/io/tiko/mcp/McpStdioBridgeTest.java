package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The tool spec the bridge hands the MCP SDK, exercised without a stdio transport (#502). */
class McpStdioBridgeTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"scope\":{\"type\":\"string\"}}}";

    @Test
    void callArgumentsReachTheHandlerAndTheResultIsReturnedAsJsonText() {
        var received = new AtomicReference<Map<String, Object>>();
        var spec = McpStdioBridge.spec(McpJsonDefaults.getMapper(), registration(args -> {
            received.set(args);
            return Map.of("components", java.util.List.of("io.example.X"));
        }));

        var result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest("list_components", Map.of("scope", "SINGLETON")));

        assertThat(spec.tool().name()).isEqualTo("list_components");
        assertThat(received.get()).containsExactlyEntriesOf(Map.of("scope", "SINGLETON"));
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo("{\"components\":[\"io.example.X\"]}");
    }

    @Test
    void missingArgumentsReachTheHandlerAsAnEmptyMap() {
        var received = new AtomicReference<Map<String, Object>>();
        var spec = McpStdioBridge.spec(McpJsonDefaults.getMapper(), registration(args -> {
            received.set(args);
            return Map.of();
        }));

        spec.callHandler().apply(null, new McpSchema.CallToolRequest("list_components", null));

        assertThat(received.get()).isEmpty();
    }

    @Test
    void aFailingHandlerBecomesAnErrorResult() {
        var spec = McpStdioBridge.spec(McpJsonDefaults.getMapper(), registration(args -> {
            throw new IllegalArgumentException("at least one filter required");
        }));

        var result = spec.callHandler().apply(null, new McpSchema.CallToolRequest("list_components", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("{\"error\":\"at least one filter required\"}");
    }

    private static ToolRegistration registration(
            java.util.function.Function<Map<String, Object>, Map<String, Object>> handler) {
        return new ToolRegistration("list_components", "List Tiko components", SCHEMA, handler);
    }

    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}
