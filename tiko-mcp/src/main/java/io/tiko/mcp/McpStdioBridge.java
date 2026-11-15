package io.tiko.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
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
    public void run() {
        // stdout is the MCP protocol channel (JSON-RPC responses), not a log destination.
        run(System.in, System.out); // NOSONAR java:S106
    }

    /**
     * Serves {@code in} until it ends, then closes the server. The transport reads on its own
     * thread and gives no sign of the end of input, so the stream itself reports it (#762): a
     * client that goes away closes stdin, and the process must not outlive it.
     */
    void run(InputStream in, OutputStream out) {
        var input = new EndOfInput(in);
        var server = start(McpJsonDefaults.getMapper(), input, out, registrations);

        LoggerHolder.LOG.log(Level.INFO, "tiko-mcp server started on stdio");

        try {
            input.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.closeGracefully();
        }
    }

    /** The version of this artifact, as the build wrote it; reported to clients as the server version. */
    static String serverVersion() {
        return VersionHolder.VERSION;
    }

    /** Lazy holder: the resource is read once, on the first server start. */
    private static final class VersionHolder {
        static final String VERSION = load();

        private static String load() {
            var unknown = "unknown";
            try (var resource = McpStdioBridge.class.getResourceAsStream("version.properties")) {
                if (resource == null) {
                    return unknown;
                }
                var properties = new Properties();
                properties.load(resource);
                return properties.getProperty("version", unknown);
            } catch (IOException unreadable) {
                return unknown;
            }
        }
    }

    /** An input stream that lets a waiting thread know when it has ended or failed. */
    private static final class EndOfInput extends FilterInputStream {
        private final CountDownLatch ended = new CountDownLatch(1);

        EndOfInput(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                return seen(super.read());
            } catch (IOException failed) {
                ended.countDown();
                throw failed;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                return seen(super.read(buffer, offset, length));
            } catch (IOException failed) {
                ended.countDown();
                throw failed;
            }
        }

        private int seen(int result) {
            if (result < 0) {
                ended.countDown();
            }
            return result;
        }

        void await() throws InterruptedException {
            ended.await();
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
                .serverInfo("tiko-mcp", serverVersion())
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
        var escaped = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    // JSON allows no raw control character inside a string (#762).
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
