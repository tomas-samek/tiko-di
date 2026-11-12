package io.tiko.examples.outbox.orders;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.NotFoundResponse;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.outbox.Messages.OrderPlaced;
import io.tiko.examples.outbox.OutboxRelay;
import io.tiko.runtime.Tiko;
import java.sql.SQLException;
import java.util.UUID;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Container container = Tiko.create(ConfigSources.classpath("orders.yaml"));
        try {
            OrderStore orders = container.get(OrderStore.class);
            Javalin app = Javalin.create(config -> {
                config.routes.put("/orders/{id}", scoped(container, ctx -> place(orders, ctx)));
                config.routes.get(
                        "/orders/{id}",
                        scoped(
                                container,
                                ctx -> ctx.json(orders.find(orderId(ctx)).orElseThrow(NotFoundResponse::new))));
            });
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                try {
                                    app.stop();
                                } finally {
                                    container.close();
                                }
                            },
                            "outbox-orders-shutdown"));
            container.get(OutboxRelay.class);
            var settings = container.get(Settings.class);
            app.start(settings.httpHost(), settings.httpPort());
            System.out.println("outbox-orders HTTP port " + app.port());
        } catch (RuntimeException | Error failure) {
            container.close();
            throw failure;
        }
    }

    private static void place(OrderStore orders, Context ctx) throws SQLException {
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

    private static Handler scoped(Container container, Handler handler) {
        return ctx -> container.runInEventScope(() -> {
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
