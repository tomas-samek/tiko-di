package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spawns the shaded {@code tiko-mcp.jar} as a subprocess, sends JSON-RPC
 * {@code initialize} + {@code tools/list} requests over stdin, asserts the
 * response advertises all expected tools.
 *
 * <p>Skipped when {@code tiko-mcp/target/tiko-mcp-0.1.0.jar} is not built —
 * keeps {@code mvn test} green on freshly-cloned trees.
 *
 * <p>The reader loop runs on a daemon thread via {@link Future#get(long,
 * TimeUnit)} so the test has a hard deadline without any {@code Thread.sleep}.
 */
class TikoMcpServerSubprocessIT {

    private static final String[] EXPECTED_TOOLS = {
        "list_components",
        "list_events",
        "get_config_schema",
        "explain_wiring",
        "reload",
        "list_wiring_errors",
        "find_dependents",
        "trace_event_flow",
        "list_profile_conflicts"
    };

    @Test
    void serverAdvertisesAllExpectedTools(@TempDir Path projectDir) throws Exception {
        var jar = Paths.get("target/tiko-mcp-0.1.0.jar");
        if (!Files.exists(jar)) {
            // Shaded jar not built yet — run `mvn package` first. Treat as pass.
            return;
        }

        // Minimal fixture so TopologyStore.loadFrom() finds at least one component.
        var topology = projectDir.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(topology.getParent());
        Files.writeString(topology, """
                {"schemaVersion":1,"module":"m",
                 "components":[{"qualifiedName":"io.example.X","scope":"SINGLETON","interfaces":[]}],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """, StandardCharsets.UTF_8);

        var pb = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-jar",
                jar.toAbsolutePath().toString(),
                projectDir.toAbsolutePath().toString());
        pb.redirectErrorStream(false);
        var proc = pb.start();

        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "mcp-it-reader");
            t.setDaemon(true);
            return t;
        });
        try (var stdin = new PrintWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8), true);
                var stdout = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {

            // MCP handshake requires three steps before tools/list works:
            //   1. initialize  → server responds
            //   2. notifications/initialized (client notification) → server transitions to INITIALIZED
            //   3. tools/list  → server can now fulfill the request via the exchangeSink
            //
            // The reader thread drives all I/O so Future.get(10s) is the only deadline —
            // no Thread.sleep anywhere.
            Future<String> accumulated = reader.submit(() -> {
                var sb = new StringBuilder();

                // Step 1: send initialize and read the response line.
                stdin.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2024-11-05\","
                        + "\"clientInfo\":{\"name\":\"it\",\"version\":\"0\"}}}");
                String initLine = stdout.readLine(); // blocks until server replies
                if (initLine != null) {
                    sb.append(initLine).append('\n');
                }

                // Step 2: send notifications/initialized so the server enters INITIALIZED state.
                stdin.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

                // Step 3: request the tools list.
                stdin.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

                // Read remaining lines until all expected tool names appear.
                String line;
                while ((line = stdout.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (allToolsPresent(sb.toString())) {
                        return sb.toString();
                    }
                }
                return sb.toString();
            });

            String result = accumulated.get(15, TimeUnit.SECONDS);

            assertThat(result)
                    .contains("list_components")
                    .contains("list_events")
                    .contains("get_config_schema")
                    .contains("explain_wiring")
                    .contains("reload")
                    .contains("list_wiring_errors")
                    .contains("find_dependents")
                    .contains("trace_event_flow")
                    .contains("list_profile_conflicts");

        } finally {
            reader.shutdownNow();
            proc.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static boolean allToolsPresent(String acc) {
        for (var tool : EXPECTED_TOOLS) {
            if (!acc.contains(tool)) return false;
        }
        return true;
    }
}
