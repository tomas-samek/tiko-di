package io.tiko.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Thin adapter from Tiko's tool classes to the MCP SDK. Kept in a separate class
 * so {@link TikoMcpServer#main(String[])} stays readable and the SDK touch points
 * live in one place.
 *
 * <p>The bridge is tool-agnostic — it takes a {@code List<ToolRegistration>} at
 * construction time and serves whatever tools it's given. Handler results (a
 * {@code Map<String,Object>}) are JSON-encoded via the SDK's default mapper and
 * returned as a single {@code TextContent} item — agents parse the JSON text
 * payload, which matches the canonical {@code topology.json} shape.
 */
public final class McpStdioBridge {

    private static final class LoggerHolder {
        static final Logger LOG = System.getLogger("io.tiko.mcp");
    }

    private final List<ToolRegistration> registrations;

    public McpStdioBridge(List<ToolRegistration> registrations) {
        this.registrations = List.copyOf(registrations);
    }

    /**
     * Start the SDK-managed stdio JSON-RPC loop. Returns when stdin closes.
     */
    public void run() throws Exception {
        // stdout is the MCP protocol channel (JSON-RPC responses), not a log destination.
        var server = start(McpJsonDefaults.getMapper(), System.in, System.out, registrations); // NOSONAR java:S106

        LoggerHolder.LOG.log(Level.INFO, "tiko-mcp server started on stdio");

        // Block the main thread; the transport drives request handling on its
        // own threads. Returns when stdin closes (transport terminates).
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.closeGracefully();
        }
    }

    /**
     * Starts an MCP server that reads JSON-RPC from {@code in} and writes responses to {@code out};
     * package-private so tests can drive it through pipes instead of the process's stdio.
     */
    static McpSyncServer start(
            McpJsonMapper mapper, InputStream in, OutputStream out, List<ToolRegistration> registrations) {
        var transport = new StdioServerTransportProvider(mapper, in, out);
        var specs = registrations.stream()
                .map(r -> spec(mapper, r))
                .toArray(McpServerFeatures.SyncToolSpecification[]::new);
        // immediateExecution: run tool handlers on the transport's single inbound thread. Offloaded
        // to concurrent threads, two handlers finishing together race on the stdio transport's
        // unicast outbound sink, and one response is dropped ("Failed to enqueue message", #509).
        return McpServer.sync(transport)
                .immediateExecution(true)
                .serverInfo("tiko-mcp", "0.1.0")
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(specs)
                .build();
    }

    /** The SDK tool spec for one registration; package-private so it can be tested in-process. */
    static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper mapper, ToolRegistration r) {
        var tool = McpSchema.Tool.builder()
                .name(r.name())
                .description(r.description())
                .inputSchema(mapper, r.schemaJson())
                .build();
        BiFunction<
                        io.modelcontextprotocol.server.McpSyncServerExchange,
                        McpSchema.CallToolRequest,
                        McpSchema.CallToolResult>
                call = (exchange, request) -> {
                    try {
                        var args = request.arguments();
                        var result = r.handler().apply(args == null ? Map.of() : args);
                        var json = mapper.writeValueAsString(result);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (Exception e) {
                        LoggerHolder.LOG.log(Level.WARNING, "Tool " + r.name() + " failed", e);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("{\"error\":\"" + escape(e.getMessage()) + "\"}")
                                .isError(true)
                                .build();
                    }
                };
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(call)
                .build();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
