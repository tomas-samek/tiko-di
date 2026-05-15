package io.tiko.examples.http;

import io.javalin.http.Context;
import io.tiko.Container;
import io.tiko.EventBus;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge layer between Javalin's HTTP machinery and Tiko's beans. The only
 * file in this example that imports both {@code io.javalin} and {@code io.tiko}.
 *
 * <p>Bridge methods are plain straight-line code — they do not call
 * {@code runInRequestScope} themselves; the {@code TikoJavalin.scoped(...)}
 * decorator wraps the entire delegate invocation at registration time.
 *
 * <p>Not a {@code @Component}: it depends on {@link EventBus}, which Tiko
 * exposes off the {@link Container} rather than via DI. {@link Main} builds
 * one instance after container bootstrap. {@link RequestId} is resolved
 * per-request via {@code container.get(RequestId.class)} from inside the
 * open scope.
 */
public final class TicketHttpRoutes {

    private final TicketService tickets;
    private final EventBus eventBus;
    private final Container container;

    public TicketHttpRoutes(TicketService tickets, EventBus eventBus, Container container) {
        this.tickets = tickets;
        this.eventBus = eventBus;
        this.container = container;
    }

    public void handleCreate(Context ctx) {
        var req = ctx.bodyAsClass(CreateTicketRequest.class);
        try {
            var ticket = tickets.create(req);
            var reqId = container.get(RequestId.class).value();
            eventBus.publish(new TicketCreated(ticket.id(), ticket.title(), reqId, Instant.now()));
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
