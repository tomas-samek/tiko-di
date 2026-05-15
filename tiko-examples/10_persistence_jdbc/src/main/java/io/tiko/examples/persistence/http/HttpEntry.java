package io.tiko.examples.persistence.http;

import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.infra.TransactionalScope;
import io.tiko.runtime.Tiko;

/**
 * HTTP entry point. Routes each request through {@code TransactionalScope.run(...)}
 * so one HTTP request = one DB transaction. The shutdown hook stops
 * Javalin before {@code container.shutdown()} so in-flight requests
 * drain before {@code @PreDestroy} runs.
 */
public final class HttpEntry {

    private HttpEntry() {}

    public static void main(String[] args) {
        Container container = Tiko.create(ConfigSources.classpath("application.yml"));
        var routes = new OrderHttpRoutes(container);

        Javalin app = Javalin.create();
        app.post(
                "/orders",
                ctx -> TransactionalScope.run(container, () -> {
                    routes.handleCreate(ctx);
                    return null;
                }));
        app.get(
                "/orders/{id}",
                ctx -> TransactionalScope.run(container, () -> {
                    routes.handleGet(ctx);
                    return null;
                }));
        app.start(portFromEnv());

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            app.stop();
                            container.shutdown();
                        },
                        "tiko-persistence-shutdown"));
    }

    private static int portFromEnv() {
        String value = System.getenv("TIKO_HTTP_PORT");
        if (value == null || value.isBlank()) return 8080;
        return Integer.parseInt(value.trim());
    }
}
