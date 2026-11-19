package io.tiko.examples.http;

import io.javalin.Javalin;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import io.tiko.events.ApplicationEndingEvent;

/**
 * The HTTP server, owned by the container. The container builds it when it starts, and
 * {@link #start()} binds the port then: a port already in use fails {@code Tiko.create(...)}.
 *
 * <p>It stops on {@link ApplicationEndingEvent}, which {@code close()} publishes before it waits
 * for async handlers: the drain in {@link Main} runs with no new request coming in. After a failed
 * boot there is no such event, and {@link #stop()} runs as {@code @PreDestroy} instead.
 */
@Component(scope = Scope.SINGLETON)
public class TicketHttpServer {

    /**
     * How long {@code stop()} lets a request in flight finish and get its response. Without it,
     * Javalin 7.2.3 lets the handler run to its end but closes the connection before the response.
     */
    private static final long STOP_TIMEOUT_MS = 5_000;

    private final HttpSettings settings;
    private final TicketHttpRoutes tickets;
    private volatile Javalin app;

    @Inject
    public TicketHttpServer(HttpSettings settings, TicketHttpRoutes tickets) {
        this.settings = settings;
        this.tickets = tickets;
    }

    @PostConstruct
    public void start() {
        app = Javalin.create(cfg -> {
                    cfg.jetty.modifyServer(server -> server.setStopTimeout(STOP_TIMEOUT_MS));
                    tickets.register(cfg.routes);
                })
                .start(settings.port());
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
