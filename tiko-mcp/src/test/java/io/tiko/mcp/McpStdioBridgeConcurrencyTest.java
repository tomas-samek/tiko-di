package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Concurrent {@code tools/call} requests over stdio each get exactly one response (#509). The
 * server is started in-process on pipes, the way {@link McpStdioBridge#run()} starts it on the
 * process's stdin/stdout.
 */
class McpStdioBridgeConcurrencyTest {

    private static final int CALLS = 10;

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @Test
    void everyConcurrentToolCallGetsItsResponse() throws Exception {
        // When handlers run concurrently, each waits until all have started, so they really
        // overlap. Run one at a time, each waits out the short timeout instead.
        var started = new CountDownLatch(CALLS);
        var tool = new ToolRegistration("slow_echo", "Echoes its argument", "{\"type\":\"object\"}", args -> {
            started.countDown();
            try {
                started.await(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("echo", String.valueOf(args.get("n")));
        });

        var toServer = new PipedOutputStream();
        var serverIn = new PipedInputStream(toServer, 1 << 16);
        var fromServer = new PipedInputStream(1 << 20);
        var serverOut = new PipedOutputStream(fromServer);
        var server = McpStdioBridge.start(McpJsonDefaults.getMapper(), serverIn, serverOut, List.of(tool));

        var responses = ConcurrentHashMap.<Integer>newKeySet();
        var reader = Thread.ofVirtual().start(() -> readIds(fromServer, responses));
        try (var client = new PrintWriter(toServer, true, StandardCharsets.UTF_8)) {
            client.println("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                    + "{\"protocolVersion\":\"2024-11-05\",\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
            awaitIds(responses, Set.of(0), Duration.ofSeconds(10));
            client.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            for (int id = 1; id <= CALLS; id++) {
                client.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"slow_echo\",\"arguments\":{\"n\":" + id + "}}}");
            }

            var expected = java.util.stream.IntStream.rangeClosed(0, CALLS)
                    .boxed()
                    .collect(java.util.stream.Collectors.toSet());
            awaitIds(responses, expected, Duration.ofSeconds(10));
            assertThat(responses).as("a response for every request").containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            server.close();
            reader.interrupt();
        }
    }

    private static void readIds(PipedInputStream from, Set<Integer> ids) {
        try (var lines = new BufferedReader(new InputStreamReader(from, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                var m = ID.matcher(line);
                if (m.find()) ids.add(Integer.parseInt(m.group(1)));
            }
        } catch (java.io.IOException ignored) {
            // the pipe closes when the server shuts down
        }
    }

    private static void awaitIds(Set<Integer> ids, Set<Integer> expected, Duration timeout) {
        org.awaitility.Awaitility.await()
                .atMost(timeout)
                .untilAsserted(() -> assertThat(ids).as("responses received").containsAll(expected));
    }
}
