package io.tiko.examples.quickstart;

import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;

/**
 * Canonical orchestrator-model bootstrap:
 *
 * <ol>
 *   <li>{@link Tiko#create} wires the container from the typed
 *       {@link AppConfig}, opens HikariCP, and fires
 *       {@link io.tiko.events.ApplicationStartedEvent} so
 *       {@link SchemaInitializer} runs the DDL before this method continues.</li>
 *   <li>{@link Javalin} is resolved from the container —
 *       {@link JavalinFactory} produced it via {@code @Produces}.</li>
 *   <li>Routes are registered against the produced instance, then the server
 *       starts. The container holds the {@code Javalin} reference; its
 *       {@code @PreDestroy} hook stops the server at shutdown.</li>
 * </ol>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Container container = Tiko.create(ConfigSources.classpath("application.yml"));
        Runtime.getRuntime().addShutdownHook(new Thread(container::shutdown, "tiko-quickstart-shutdown"));

        var routes = new NoteRoutes(container.get(NoteRepository.class), container.getEventBus());
        Javalin app = container.get(Javalin.class);
        app.post("/notes", routes::handleCreate);
        app.get("/notes/{id}", routes::handleGet);

        int port = container.get(AppConfig.class).server().port();
        app.start(port);
    }
}
