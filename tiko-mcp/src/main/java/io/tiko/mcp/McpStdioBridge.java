package io.tiko.mcp;

import io.tiko.mcp.tools.ExplainWiringTool;
import io.tiko.mcp.tools.GetConfigSchemaTool;
import io.tiko.mcp.tools.ListComponentsTool;
import io.tiko.mcp.tools.ListEventsTool;

/**
 * Thin adapter from Tiko's tool classes to the MCP SDK. Kept in a separate class
 * so {@link TikoMcpServer#main(String[])} stays readable and the SDK touch points
 * live in one place.
 *
 * <p><strong>Implementation note:</strong> the SDK class names and method
 * signatures depend on the version pinned in {@code tiko-bom}. Implement
 * {@link #run()} using the SDK's builder/server API — register each of the four
 * tool names with its input JSON schema and route the handler to the matching
 * {@code *.execute(args)} method below.
 */
public final class McpStdioBridge {

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
     *
     * <p>Wire the four tools using the MCP SDK's tool-registration API. Each tool's
     * input schema is shown below for the implementer to inline at the SDK call site.
     */
    public void run() throws Exception {
        // list_components input schema
        String listComponentsSchema = """
                {"type":"object","properties":{
                   "scope":{"type":"string","enum":["SINGLETON","REQUEST","EVENT","PROTOTYPE"]},
                   "interface":{"type":"string"}}}""";

        // list_events input schema
        String listEventsSchema = """
                {"type":"object","properties":{
                   "eventType":{"type":"string"}}}""";

        // get_config_schema input schema
        String getConfigSchemaSchema = """
                {"type":"object","properties":{
                   "prefix":{"type":"string"}}}""";

        // explain_wiring input schema
        String explainWiringSchema = """
                {"type":"object","properties":{
                   "componentFqn":{"type":"string"},
                   "maxDepth":{"type":"integer","default":10}},
                 "required":["componentFqn"]}""";

        // TODO (Task 22): replace the throw below with the real SDK API.
        // The handler for each tool should return the JSON the SDK wraps as tool
        // result content; route invocations to the matching *.execute(args) methods:
        //   listComponents.execute(args)
        //   listEvents.execute(args)
        //   getConfigSchema.execute(args)
        //   explainWiring.execute(args)
        // The four schema strings above belong at their respective SDK registration
        // call sites — inline them there rather than keeping them as locals.
        throw new UnsupportedOperationException("Wire the four tools (list_components, list_events, get_config_schema,"
                + " explain_wiring) to the MCP SDK pinned in tiko-bom."
                + " See inline schema strings above.");
    }
}
