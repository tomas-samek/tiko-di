package io.tiko.examples.quickstart;

import io.javalin.Javalin;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import io.tiko.events.ApplicationEndingEvent;

/**
 * The HTTP server, owned by the container. The container builds it when it starts, as it builds
 * every SINGLETON component, and {@link #start()} binds the port then: a port already in use
 * fails {@code Tiko.daemon(...)}, and the container shuts down what it had started.
 *
 * <p>It stops on {@link ApplicationEndingEvent}, which comes before the container waits for async
 * work, so no new request arrives while that work drains. After a failed boot there is no such
 * event, and {@link #stop()} runs as {@code @PreDestroy} instead.
 *
 * <p>Javalin 7 takes routes only inside {@code Javalin.create(...)}, so each route group is a
 * constructor parameter and registers its own paths there.
 */
@Component(scope = Scope.SINGLETON)
public class HttpServer {

    /**
     * How long {@code stop()} lets a request in flight finish and get its response. Without it,
     * Javalin 7.2.3 lets the handler run to its end but closes the connection before the response.
     */
    private static final long STOP_TIMEOUT_MS = 5_000;

    private final ServerConfig config;
    private final NoteRoutes notes;
    private volatile Javalin app;

    @Inject
    public HttpServer(AppConfig config, NoteRoutes notes) {
        this.config = config.server();
        this.notes = notes;
    }

    @PostConstruct
    public void start() {
        app = Javalin.create(cfg -> {
                    cfg.jetty.modifyServer(server -> server.setStopTimeout(STOP_TIMEOUT_MS));
                    notes.register(cfg.routes);
                })
                .start(config.port());
    }

    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        stop();
    }

    @PreDestroy
    public void stop() {
        Javalin running = app;
        app = null;
        if (running != null) running.stop();
    }

    /** The port the server listens on; the configured one, or the one the OS picked for {@code 0}. */
    public int port() {
        return app.port();
    }
}
