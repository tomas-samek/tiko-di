package io.tiko.examples.http;

import io.javalin.Javalin;
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
 * <p>Runs Javalin, fires a ticket-creation request that triggers a 2-second
 * async audit handler, then stops Javalin while the audit is still in flight.
 * The container's {@code close()} (via try-with-resources) waits for the
 * in-flight async work to drain before tearing the executor down.
 *
 * <p>Expected console output:
 *
 * <pre>{@code
 * [main] Stopping HTTP server (async work still running)...
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
            TicketHttpRoutes routes =
                    new TicketHttpRoutes(container.get(TicketService.class), container.getEventBus(), container);

            Javalin app = Javalin.create();
            app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate));
            app.get("/tickets/{id}", TikoJavalin.scoped(container, routes::handleGet));

            int port = portFromEnv();
            app.start(port);

            // Trigger a real ticket-creation request. SlowAuditService receives the
            // resulting event asynchronously and sleeps ~2s.
            fireCreateTicketRequest(port);

            // Stop the HTTP layer while async audit is still in flight.
            System.out.println("[main] Stopping HTTP server (async work still running)...");
            app.stop();
        } // container.close() drains the in-flight slow handler before returning.

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

    private static int portFromEnv() {
        String value = System.getenv("TIKO_HTTP_PORT");
        if (value == null || value.isBlank()) return 8080;
        return Integer.parseInt(value.trim());
    }
}
