package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spawns the shaded {@code tiko-mcp} jar as a subprocess and speaks JSON-RPC over stdin/stdout:
 * {@code initialize}, {@code tools/list} (every expected tool is advertised) and two
 * {@code tools/call}s whose {@code scope} argument must reach the tool, so a filter that excludes
 * the fixture component returns it and one that matches does.
 *
 * <p>Runs under failsafe ({@code mvn verify}), after {@code package} has built the shaded jar.
 * The reader loop runs on a daemon thread via {@link Future#get(long, TimeUnit)} so the test has a
 * hard deadline without any {@code Thread.sleep}.
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
    void serverListsToolsAndPassesCallArgumentsThrough(@TempDir Path projectDir) throws Exception {
        var jar = shadedJar();

        // Minimal fixture so TopologyStore.loadFrom() finds one SINGLETON component.
        var topology = projectDir.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(topology.getParent());
        Files.writeString(topology, """
                {"schemaVersion":1,"module":"m",
                 "components":[{"qualifiedName":"io.example.X","scope":"SINGLETON","interfaces":[]}],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """, StandardCharsets.UTF_8);

        var pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
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
        // Responses keyed by JSON-RPC id; shared so a timeout can report what did arrive.
        var byId = new ConcurrentHashMap<Integer, String>();
        // No try-with-resources: closing a reader another thread is blocked on deadlocks. Killing
        // the process in `finally` ends the blocked readLine() instead.
        var stdin = new PrintWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8), true);
        var stdout = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        try {
            // MCP handshake: initialize → response, then notifications/initialized, after which
            // requests are served. One request at a time: each waits for its response before the
            // next is sent.
            Future<?> responses = reader.submit(() -> {
                exchange(
                        stdin,
                        stdout,
                        byId,
                        1,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                + "\"params\":{\"protocolVersion\":\"2024-11-05\","
                                + "\"clientInfo\":{\"name\":\"it\",\"version\":\"0\"}}}");
                stdin.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                exchange(stdin, stdout, byId, 2, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
                exchange(
                        stdin,
                        stdout,
                        byId,
                        3,
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"list_components\",\"arguments\":{\"scope\":\"PROTOTYPE\"}}}");
                exchange(
                        stdin,
                        stdout,
                        byId,
                        4,
                        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"list_components\",\"arguments\":{\"scope\":\"SINGLETON\"}}}");
                return null;
            });

            try {
                responses.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("no response to every request within 15 s; received: " + byId, e);
            }

            assertThat(byId.get(2)).as("tools/list").contains(EXPECTED_TOOLS);
            assertThat(byId.get(3))
                    .as("list_components with scope=PROTOTYPE excludes the SINGLETON fixture")
                    .contains("\"result\"")
                    .doesNotContain("io.example.X");
            assertThat(byId.get(4))
                    .as("list_components with scope=SINGLETON returns the fixture")
                    .contains("io.example.X");

        } finally {
            proc.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            reader.shutdownNow();
            stdin.close();
        }
    }

    /** The shaded jar {@code package} built for the current version (not {@code original-…}). */
    private static Path shadedJar() throws IOException {
        try (var files = Files.list(Paths.get("target"))) {
            var jar = files.filter(p -> {
                        var name = p.getFileName().toString();
                        return name.startsWith("tiko-mcp-")
                                && name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .sorted()
                    .findFirst();
            assertThat(jar)
                    .as("shaded tiko-mcp jar in target/ (run under mvn verify)")
                    .isPresent();
            return jar.get();
        }
    }

    /** Sends one request and reads stdout until the response with the same id arrives. */
    private static void exchange(
            PrintWriter stdin, BufferedReader stdout, Map<Integer, String> byId, int id, String request)
            throws IOException {
        stdin.println(request);
        String line;
        while (!byId.containsKey(id) && (line = stdout.readLine()) != null) {
            collect(byId, line);
        }
    }

    private static void collect(Map<Integer, String> byId, String line) {
        if (line == null) return;
        var m = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(line);
        if (m.find()) byId.put(Integer.parseInt(m.group(1)), line);
    }
}
