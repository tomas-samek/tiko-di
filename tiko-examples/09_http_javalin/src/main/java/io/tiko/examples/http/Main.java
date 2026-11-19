package io.tiko.examples.http;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Bootstrap + runnable drain demo. The shutdown-timeout window comes from
 * {@code tiko.shutdownTimeout: PT5S} in {@code config.yaml} — this example
 * demonstrates the YAML-config path introduced by #48. (The equivalent
 * programmatic form is {@code TikoOptions.builder().shutdownTimeout(Duration.ofSeconds(5))}.)
 *
 * <p>The container starts the HTTP server ({@link TicketHttpServer}). This demo fires a
 * ticket-creation request that triggers a 2-second async audit handler, then closes the
 * container while the audit is still in flight. {@code close()} (via try-with-resources) stops
 * the HTTP server first, then waits for the in-flight async work to drain before tearing the
 * executor down. A long-running service would call {@code Tiko.daemon(opts).awaitShutdown()}
 * instead, and the same happens on {@code Ctrl+C} / {@code SIGTERM}.
 *
 * <p>Expected console output (the first two lines come in either order):
 *
 * <pre>{@code
 * [main] Closing the container (async work still running)...
 * [async] slow audit work starting for ticket <uuid>
 * [async] slow audit work complete for ticket <uuid>
 * [main] Container closed cleanly.
 * }</pre>
 *
 * <p><strong>Error caveat:</strong> A JVM {@link Error} (e.g. {@code OutOfMemoryError},
 * {@code StackOverflowError}) bypasses this graceful drain — the JVM may tear down
 * threads abruptly when in an unrecoverable state. This is a JVM-level contract,
 * not something Tiko controls. For everything short of a JVM-level fatal, the
 * configured {@code shutdownTimeout} bounds the wait.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        // Timeout sourced from YAML — see config.yaml -> tiko.shutdownTimeout.
        TikoOptions opts = TikoOptions.builder()
                .configSource(ConfigSources.classpath("config.yaml"))
                .build();

        try (Container container = Tiko.create(opts)) {
            int port = container.get(TicketHttpServer.class).port();

            // Arm the slow audit handler so this one event takes the 2s sleep path —
            // gives container.close() something to drain. Other handlers are unaffected.
            container.get(SlowAuditService.class).expectOne();

            // Trigger a real ticket-creation request. SlowAuditService receives the
            // resulting event asynchronously and sleeps ~2s.
            fireCreateTicketRequest(port);

            // Close while the async audit is still in flight: the server stops first.
            System.out.println("[main] Closing the container (async work still running)...");
        } // container.close() stops the server, then drains the in-flight slow handler before returning.

        System.out.println("[main] Container closed cleanly.");
    }

    private static void fireCreateTicketRequest(int port) throws Exception {
        // Manual JSON literal — keeps the demo's main path free of an ObjectMapper
        // dependency. Shape mirrors CreateTicketRequest(String title).
        String body = "{\"title\":\"drain demo\"}";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/tickets"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Unexpected status " + response.statusCode() + ": " + response.body());
        }
    }
}
