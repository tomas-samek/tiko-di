package io.tiko.examples.persistence.http;

import io.javalin.http.Context;
import io.tiko.Container;
import io.tiko.examples.persistence.domain.CreateOrderRequest;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import io.tiko.examples.persistence.repo.OrderRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge between Javalin's HTTP machinery and the persistence layer.
 * Not a {@code @Component}: it depends on {@link Container} (not
 * DI-injectable). Constructed once in {@link HttpEntry} and held for
 * the server's lifetime; per-request resolution of repositories happens
 * via {@code container.get(...)} inside the open REQUEST scope.
 */
public final class OrderHttpRoutes {

    private final Container container;

    public OrderHttpRoutes(Container container) {
        this.container = container;
    }

    public void handleCreate(Context ctx) {
        var req = ctx.bodyAsClass(CreateOrderRequest.class);
        if (req.customer() == null || req.customer().isBlank()) {
            throw new IllegalArgumentException("customer must not be blank");
        }
        var order = new Order(UUID.randomUUID(), req.customer(), "NEW", Instant.now(), req.items());
        try {
            var repo = container.get(OrderRepository.class);
            // Insert the orders row + items one-at-a-time so a poison item
            // mid-batch leaves the orders row already INSERTed in the tx.
            // The cookbook's rollback test depends on this: the
            // TransactionalScope.run wrapper must roll BOTH back together.
            repo.insertHeader(order);
            for (OrderItem item : req.items()) {
                if (item.qty() < 0) {
                    throw new IllegalArgumentException("qty must not be negative (line " + item.lineNo() + ")");
                }
                repo.insertItem(order.id(), item);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        ctx.status(201).json(order);
    }

    public void handleGet(Context ctx) {
        var id = UUID.fromString(ctx.pathParam("id"));
        try {
            container
                    .get(OrderRepository.class)
                    .findById(id)
                    .ifPresentOrElse(o -> ctx.status(200).json(o), () -> ctx.status(404));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
