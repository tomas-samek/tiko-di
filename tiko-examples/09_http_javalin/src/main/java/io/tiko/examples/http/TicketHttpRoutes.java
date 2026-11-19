package io.tiko.examples.http;

import static io.tiko.examples.http.TikoJavalin.scoped;

import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.UnitOfWork;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge layer between Javalin's HTTP machinery and Tiko's beans. The only
 * file in this example that imports both {@code io.javalin} and {@code io.tiko}
 * besides the server and the middleware.
 *
 * <p>Bridge methods are plain straight-line code — they do not call
 * {@code runInEventScope} themselves; {@link #register} wraps each one with
 * {@code TikoJavalin.scoped(...)}. {@link RequestId} is EVENT-scoped, so this
 * SINGLETON gets a proxy that resolves the current request's instance on every call.
 */
@Component(scope = Scope.SINGLETON)
public class TicketHttpRoutes {

    private final UnitOfWork unitOfWork;
    private final TicketService tickets;
    private final EventBus eventBus;
    private final RequestId requestId;

    @Inject
    public TicketHttpRoutes(UnitOfWork unitOfWork, TicketService tickets, EventBus eventBus, RequestId requestId) {
        this.unitOfWork = unitOfWork;
        this.tickets = tickets;
        this.eventBus = eventBus;
        this.requestId = requestId;
    }

    /** Registers this group's paths, each handler in a unit of work of its own. */
    public void register(RoutesConfig routes) {
        routes.post("/tickets", scoped(unitOfWork, this::handleCreate));
        routes.get("/tickets/{id}", scoped(unitOfWork, this::handleGet));
    }

    public void handleCreate(Context ctx) {
        var req = ctx.bodyAsClass(CreateTicketRequest.class);
        try {
            var ticket = tickets.create(req);
            eventBus.publish(new TicketCreated(ticket.id(), ticket.title(), requestId.value(), Instant.now()));
            ctx.status(201).json(ticket);
        } catch (IllegalArgumentException badInput) {
            ctx.status(400).json(java.util.Map.of("error", badInput.getMessage()));
        }
    }

    public void handleGet(Context ctx) {
        var id = UUID.fromString(ctx.pathParam("id"));
        tickets.find(id).ifPresentOrElse(t -> ctx.status(200).json(t), () -> ctx.status(404));
    }
}
