package io.tiko.examples.outbox.orders;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;
import io.tiko.examples.outbox.Database;
import io.tiko.examples.outbox.JdbcOutbox;
import io.tiko.examples.outbox.Messages;
import io.tiko.examples.outbox.Messages.OrderPlaced;
import io.tiko.examples.outbox.Messages.ReservationDecided;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@Component(scope = Scope.SINGLETON)
public class OrderStore {
    private final DataSource database;

    public OrderStore(DataSource database) {
        this.database = database;
    }

    @PostConstruct
    public void initialize() throws SQLException {
        Database.initialize(database, JdbcOutbox.SCHEMA, """
                CREATE TABLE IF NOT EXISTS orders (
                    order_id UUID PRIMARY KEY,
                    sku VARCHAR(40) NOT NULL,
                    quantity INTEGER NOT NULL CHECK (quantity > 0),
                    status VARCHAR(12) NOT NULL
                )
                """);
    }

    public Optional<Order> place(OrderPlaced order) throws SQLException {
        try {
            return Database.transaction(database, transaction -> {
                try (var insert = transaction.prepareStatement(
                        "INSERT INTO orders (order_id, sku, quantity, status) VALUES (?, ?, ?, 'PENDING')")) {
                    insert.setObject(1, order.orderId());
                    insert.setString(2, order.sku());
                    insert.setInt(3, order.quantity());
                    insert.executeUpdate();
                }
                JdbcOutbox.enqueue(transaction, Messages.ORDERS, order.orderId().toString(), order);
                return Optional.of(new Order(order.orderId(), order.sku(), order.quantity(), "PENDING"));
            });
        } catch (SQLException failure) {
            if (!"23505".equals(failure.getSQLState())) {
                throw failure;
            }
            // A concurrent retry may have won the primary-key insert.
            var existing = find(order.orderId());
            if (existing.isEmpty()) {
                throw failure;
            }
            return existing.filter(saved -> saved.sku().equals(order.sku()) && saved.quantity() == order.quantity());
        }
    }

    public Optional<Order> find(UUID orderId) throws SQLException {
        try (var connection = database.getConnection()) {
            return find(connection, orderId);
        }
    }

    public void record(ReservationDecided result) throws SQLException {
        Database.transaction(database, transaction -> {
            try (var update = transaction.prepareStatement(
                    "UPDATE orders SET status = ? WHERE order_id = ? AND status = 'PENDING'")) {
                update.setString(1, result.decision().name());
                update.setObject(2, result.orderId());
                if (update.executeUpdate() == 0
                        && find(transaction, result.orderId())
                                .filter(order ->
                                        order.status().equals(result.decision().name()))
                                .isEmpty()) {
                    throw new IllegalStateException("Unexpected reservation result for order " + result.orderId());
                }
            }
            return null;
        });
    }

    private static Optional<Order> find(Connection connection, UUID orderId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT sku, quantity, status FROM orders WHERE order_id = ?")) {
            query.setObject(1, orderId);
            try (var rows = query.executeQuery()) {
                return rows.next()
                        ? Optional.of(new Order(orderId, rows.getString(1), rows.getInt(2), rows.getString(3)))
                        : Optional.empty();
            }
        }
    }

    public record Order(UUID orderId, String sku, int quantity, String status) {}
}
