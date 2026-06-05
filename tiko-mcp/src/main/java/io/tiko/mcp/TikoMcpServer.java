package io.tiko.mcp;

import io.tiko.mcp.tools.ExplainProxyTool;
import io.tiko.mcp.tools.ExplainWiringTool;
import io.tiko.mcp.tools.FindDependentsTool;
import io.tiko.mcp.tools.GetConfigSchemaTool;
import io.tiko.mcp.tools.GetGeneratedArtifactTool;
import io.tiko.mcp.tools.ListComponentsTool;
import io.tiko.mcp.tools.ListEventsTool;
import io.tiko.mcp.tools.ListLifecycleHooksTool;
import io.tiko.mcp.tools.ListProfileConflictsTool;
import io.tiko.mcp.tools.ListWiringErrorsTool;
import io.tiko.mcp.tools.ReloadTool;
import io.tiko.mcp.tools.TopologyOverviewTool;
import io.tiko.mcp.tools.TraceEventFlowTool;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Entrypoint for the {@code tiko-mcp} runnable jar. Reads {@code args[0]} as the
 * project root, walks the multi-module classpath layout for
 * {@code META-INF/tiko/topology.json}, {@code config-schema.json}, and
 * {@code wiring-errors.json}, then serves all registered read-only MCP tools over stdio.
 *
 * <p>Stdout is reserved for JSON-RPC framing — all logging goes to stderr via
 * {@link java.lang.System.Logger}.
 */
public final class TikoMcpServer {

    private static final class LoggerHolder {
        static final Logger LOG = System.getLogger("io.tiko.mcp");
    }

    private TikoMcpServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            LoggerHolder.LOG.log(Level.ERROR, "Usage: java -jar tiko-mcp.jar <project-dir>");
            System.exit(2);
        }
        Path projectRoot = Paths.get(args[0]).toAbsolutePath();
        LoggerHolder.LOG.log(Level.INFO, "Loading topology from {0}", projectRoot);

        var store = TopologyStore.loadFrom(projectRoot);
        LoggerHolder.LOG.log(
                Level.INFO,
                "Loaded {0} components, {1} configurations",
                store.components().size(),
                store.configurations().size());

        var listComponents = new ListComponentsTool(store);
        var listEvents = new ListEventsTool(store);
        var getConfigSchema = new GetConfigSchemaTool(store);
        var explainWiring = new ExplainWiringTool(store);
        var reload = new ReloadTool(store);
        var listWiringErrors = new ListWiringErrorsTool(store);
        var findDependents = new FindDependentsTool(store);
        var traceEventFlow = new TraceEventFlowTool(store);
        var listProfileConflicts = new ListProfileConflictsTool(store);
        var listLifecycleHooks = new ListLifecycleHooksTool(store);
        var topologyOverview = new TopologyOverviewTool(store);
        var explainProxy = new ExplainProxyTool(store);
        var getGeneratedArtifact = new GetGeneratedArtifactTool(store);

        var registrations = List.of(
                new ToolRegistration(ListComponentsTool.NAME, "List Tiko components", """
                        {"type":"object","properties":{
                           "scope":{"type":"string","enum":["SINGLETON","REQUEST","EVENT","PROTOTYPE"]},
                           "interface":{"type":"string"},
                           "profile":{"type":"string"}}}""", listComponents::execute),
                new ToolRegistration(ListEventsTool.NAME, "List Tiko events", """
                        {"type":"object","properties":{
                           "eventType":{"type":"string"}}}""", listEvents::execute),
                new ToolRegistration(
                        GetConfigSchemaTool.NAME, "Get @Configuration JSON schema", """
                        {"type":"object","properties":{
                           "prefix":{"type":"string"}}}""", getConfigSchema::execute),
                new ToolRegistration(
                        ExplainWiringTool.NAME,
                        "Explain dependency wiring for a component",
                        """
                        {"type":"object","properties":{
                           "componentFqn":{"type":"string"},
                           "maxDepth":{"type":"integer","default":10},
                           "profile":{"type":"string"}},
                         "required":["componentFqn"]}""",
                        explainWiring::execute),
                new ToolRegistration(ReloadTool.NAME, "Reload Tiko topology from disk", """
                        {"type":"object","properties":{}}""", reload::execute),
                new ToolRegistration(
                        ListWiringErrorsTool.NAME,
                        "List Tiko wiring-error diagnostics",
                        """
                        {"type":"object","properties":{}}""",
                        listWiringErrors::execute),
                new ToolRegistration(
                        FindDependentsTool.NAME,
                        "Find reverse dependents of a component",
                        """
                        {"type":"object","properties":{
                           "componentFqn":{"type":"string"},
                           "transitive":{"type":"boolean","default":false}},
                         "required":["componentFqn"]}""",
                        findDependents::execute),
                new ToolRegistration(
                        TraceEventFlowTool.NAME,
                        "Trace @EventTrigger DAG from an event type",
                        """
                        {"type":"object","properties":{
                           "eventType":{"type":"string"},
                           "maxDepth":{"type":"integer","default":20}},
                         "required":["eventType"]}""",
                        traceEventFlow::execute),
                new ToolRegistration(
                        ListProfileConflictsTool.NAME,
                        "List components implementing the same type under disjoint profiles",
                        """
                        {"type":"object","properties":{}}""",
                        listProfileConflicts::execute),
                new ToolRegistration(
                        ListLifecycleHooksTool.NAME,
                        "List @PostConstruct / @PreDestroy methods across components",
                        """
                        {"type":"object","properties":{
                           "phase":{"type":"string","enum":["POST_CONSTRUCT","PRE_DESTROY"]}}}""",
                        listLifecycleHooks::execute),
                new ToolRegistration(
                        TopologyOverviewTool.NAME,
                        "Orientation snapshot: totals, scopes, conflict flag, suggested next queries",
                        "{\"type\":\"object\",\"properties\":{}}",
                        topologyOverview::execute),
                new ToolRegistration(
                        ExplainProxyTool.NAME,
                        "Explain the cross-scope proxy on a component (interface, proxied methods, reason)",
                        """
                        {"type":"object","properties":{
                           "componentFqn":{"type":"string"}},
                         "required":["componentFqn"]}""",
                        explainProxy::execute),
                new ToolRegistration(
                        GetGeneratedArtifactTool.NAME,
                        "Get path + summary (no contents) of a generated source for a given kind/component",
                        """
                        {"type":"object","properties":{
                           "kind":{"type":"string","enum":["FACTORY","PROXY","CONTAINER","EVENT_REGISTRY","CONFIG_BINDER"]},
                           "componentFqn":{"type":"string"}},
                         "required":["kind"]}""",
                        getGeneratedArtifact::execute));

        new McpStdioBridge(registrations).run();
    }
}
