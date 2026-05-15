package io.tiko.examples.http;

import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.runtime.Tiko;

/**
 * Bootstrap. Builds the Tiko container, fetches the bridge bean, builds a
 * Javalin app, registers routes through the {@link TikoJavalin#scoped}
 * decorator so each request runs inside a Tiko request scope, and wires a
 * shutdown hook in shutdown-safe order.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Container container = Tiko.create();
        TicketHttpRoutes routes =
                new TicketHttpRoutes(container.get(TicketService.class), container.getEventBus(), container);

        Javalin app = Javalin.create();
        app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate));
        app.get("/tickets/{id}", TikoJavalin.scoped(container, routes::handleGet));

        int port = portFromEnv();
        app.start(port);

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            // Stop Javalin first: drains in-flight requests before Tiko teardown
                            // so @PreDestroy / AutoCloseable.close() never run on a bean still
                            // being read by an HTTP worker.
                            app.stop();
                            container.shutdown();
                        },
                        "tiko-http-shutdown"));
    }

    private static int portFromEnv() {
        String value = System.getenv("TIKO_HTTP_PORT");
        if (value == null || value.isBlank()) return 8080;
        return Integer.parseInt(value.trim());
    }
}
