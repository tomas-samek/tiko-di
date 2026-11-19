package io.tiko.examples.outbox.orders;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.NotFoundResponse;
import io.tiko.Scope;
import io.tiko.UnitOfWork;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import io.tiko.events.ApplicationEndingEvent;
import io.tiko.examples.outbox.Messages.OrderPlaced;
import io.tiko.examples.outbox.OutboxRelay;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The orders HTTP API, owned by the container. The container builds it when it starts, and
 * {@link #start()} binds the port then: a port already in use fails {@code Tiko.daemon(...)}.
 * It takes the {@link OutboxRelay}, so the relay starts with the container too, and it stops on
 * {@link ApplicationEndingEvent}, before the container closes the relay.
 */
@Component(scope = Scope.SINGLETON)
public class OrdersHttpServer {
    private final UnitOfWork unitOfWork;
    private final OrderStore orders;
    private final Settings settings;
    private volatile Javalin app;

    @Inject
    public OrdersHttpServer(UnitOfWork unitOfWork, OrderStore orders, Settings settings, OutboxRelay relay) {
        // The relay is taken only so that it runs from boot: it relays what the routes commit.
        this.unitOfWork = unitOfWork;
        this.orders = orders;
        this.settings = settings;
    }

    @PostConstruct
    public void start() {
        app = Javalin.create(config -> {
                    config.routes.put("/orders/{id}", inUnit(this::place));
                    config.routes.get(
                            "/orders/{id}",
                            inUnit(ctx -> ctx.json(orders.find(orderId(ctx)).orElseThrow(NotFoundResponse::new))));
                })
                .start(settings.httpHost(), settings.httpPort());
        System.out.println("outbox-orders HTTP port " + app.port());
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

    private void place(Context ctx) throws SQLException {
        UUID id = orderId(ctx);
        OrderRequest request = ctx.bodyAsClass(OrderRequest.class);
        OrderPlaced order;
        try {
            order = new OrderPlaced(id, request.sku(), request.quantity());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestResponse(invalid.getMessage());
        }
        ctx.status(202).json(orders.place(order).orElseThrow(() -> new ConflictResponse("Order ID already used")));
    }

    private static UUID orderId(Context ctx) {
        try {
            return UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestResponse("Order ID must be a UUID");
        }
    }

    /** One unit of work per request. */
    private Handler inUnit(Handler handler) {
        return ctx -> unitOfWork.runInEventScope(() -> {
            try {
                handler.handle(ctx);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Order request failed", failure);
            }
        });
    }

    public record OrderRequest(String sku, int quantity) {}
}
