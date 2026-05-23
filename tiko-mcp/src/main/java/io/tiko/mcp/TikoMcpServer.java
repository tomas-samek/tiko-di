package io.tiko.mcp;

import io.tiko.mcp.tools.ExplainWiringTool;
import io.tiko.mcp.tools.FindDependentsTool;
import io.tiko.mcp.tools.GetConfigSchemaTool;
import io.tiko.mcp.tools.ListComponentsTool;
import io.tiko.mcp.tools.ListEventsTool;
import io.tiko.mcp.tools.ListProfileConflictsTool;
import io.tiko.mcp.tools.ListWiringErrorsTool;
import io.tiko.mcp.tools.ReloadTool;
import io.tiko.mcp.tools.TraceEventFlowTool;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entrypoint for the {@code tiko-mcp} runnable jar. Reads {@code args[0]} as the
 * project root, walks the multi-module classpath layout for
 * {@code META-INF/tiko/topology.json} and {@code config-schema.json}, then serves
 * the four read-only MCP tools over stdio.
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
            System.err.println("Usage: java -jar tiko-mcp.jar <project-dir>");
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

        new McpStdioBridge(
                        listComponents,
                        listEvents,
                        getConfigSchema,
                        explainWiring,
                        reload,
                        listWiringErrors,
                        findDependents,
                        traceEventFlow,
                        listProfileConflicts)
                .run();
    }
}
