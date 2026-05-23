package io.tiko.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.tiko.mcp.tools.ExplainWiringTool;
import io.tiko.mcp.tools.GetConfigSchemaTool;
import io.tiko.mcp.tools.ListComponentsTool;
import io.tiko.mcp.tools.ListEventsTool;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Thin adapter from Tiko's tool classes to the MCP SDK. Kept in a separate class
 * so {@link TikoMcpServer#main(String[])} stays readable and the SDK touch points
 * live in one place.
 *
 * <p>Each tool's input schema is inlined at its registration site below. Handler
 * results (a {@code Map<String,Object>}) are JSON-encoded via the SDK's default
 * mapper and returned as a single {@code TextContent} item — agents parse the
 * JSON text payload, which matches the canonical {@code topology.json} shape.
 */
public final class McpStdioBridge {

    private static final class LoggerHolder {
        static final Logger LOG = System.getLogger("io.tiko.mcp");
    }

    private final ListComponentsTool listComponents;
    private final ListEventsTool listEvents;
    private final GetConfigSchemaTool getConfigSchema;
    private final ExplainWiringTool explainWiring;

    public McpStdioBridge(
            ListComponentsTool listComponents,
            ListEventsTool listEvents,
            GetConfigSchemaTool getConfigSchema,
            ExplainWiringTool explainWiring) {
        this.listComponents = listComponents;
        this.listEvents = listEvents;
        this.getConfigSchema = getConfigSchema;
        this.explainWiring = explainWiring;
    }

    /**
     * Start the SDK-managed stdio JSON-RPC loop. Returns when stdin closes.
     */
    public void run() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        var transport = new StdioServerTransportProvider(mapper);

        var listComponentsSchema = """
                {"type":"object","properties":{
                   "scope":{"type":"string","enum":["SINGLETON","REQUEST","EVENT","PROTOTYPE"]},
                   "interface":{"type":"string"}}}""";

        var listEventsSchema = """
                {"type":"object","properties":{
                   "eventType":{"type":"string"}}}""";

        var getConfigSchemaSchema = """
                {"type":"object","properties":{
                   "prefix":{"type":"string"}}}""";

        var explainWiringSchema = """
                {"type":"object","properties":{
                   "componentFqn":{"type":"string"},
                   "maxDepth":{"type":"integer","default":10}},
                 "required":["componentFqn"]}""";

        var server = McpServer.sync(transport)
                .serverInfo("tiko-mcp", "0.1.0")
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                        spec(
                                mapper,
                                ListComponentsTool.NAME,
                                "List Tiko components",
                                listComponentsSchema,
                                listComponents::execute),
                        spec(mapper, ListEventsTool.NAME, "List Tiko events", listEventsSchema, listEvents::execute),
                        spec(
                                mapper,
                                GetConfigSchemaTool.NAME,
                                "Get @Configuration JSON schema",
                                getConfigSchemaSchema,
                                getConfigSchema::execute),
                        spec(
                                mapper,
                                ExplainWiringTool.NAME,
                                "Explain dependency wiring for a component",
                                explainWiringSchema,
                                explainWiring::execute))
                .build();

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

    private static McpServerFeatures.SyncToolSpecification spec(
            McpJsonMapper mapper,
            String name,
            String description,
            String inputSchemaJson,
            Function<Map<String, Object>, Map<String, Object>> handler) {
        var tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(mapper, inputSchemaJson)
                .build();
        BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult>
                call = (exchange, args) -> {
                    try {
                        var result = handler.apply(args == null ? Map.of() : args);
                        var json = mapper.writeValueAsString(result);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (Exception e) {
                        LoggerHolder.LOG.log(Level.WARNING, "Tool " + name + " failed", e);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("{\"error\":\"" + escape(e.getMessage()) + "\"}")
                                .isError(true)
                                .build();
                    }
                };
        return new McpServerFeatures.SyncToolSpecification(tool, call);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
