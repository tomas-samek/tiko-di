package io.tiko.examples.outbox.warehouse;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;
import io.tiko.examples.outbox.Database;
import io.tiko.examples.outbox.JdbcOutbox;
import io.tiko.examples.outbox.Messages;
import io.tiko.examples.outbox.Messages.Decision;
import io.tiko.examples.outbox.Messages.OrderPlaced;
import io.tiko.examples.outbox.Messages.ReservationDecided;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

@Component(scope = Scope.SINGLETON)
public class WarehouseStore {
    private final DataSource database;

    public WarehouseStore(DataSource database) {
        this.database = database;
    }

    @PostConstruct
    public void initialize() throws SQLException {
        Database.initialize(database, JdbcOutbox.SCHEMA, """
                CREATE TABLE IF NOT EXISTS stock (
                    sku VARCHAR(40) PRIMARY KEY,
                    available INTEGER NOT NULL CHECK (available >= 0)
                )
                """, """
                CREATE TABLE IF NOT EXISTS reservations (
                    order_id UUID PRIMARY KEY,
                    decision VARCHAR(12) NOT NULL
                )
                """, """
                INSERT INTO stock (sku, available)
                SELECT 'widget', 10 WHERE NOT EXISTS (SELECT 1 FROM stock WHERE sku = 'widget')
                """);
    }

    public ReservationDecided reserve(OrderPlaced order) throws SQLException {
        return Database.transaction(database, transaction -> {
            Decision existing = recordedDecision(transaction, order);
            if (existing != null) {
                return new ReservationDecided(order.orderId(), existing);
            }

            Decision decision;
            try (var update = transaction.prepareStatement(
                    "UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?")) {
                update.setInt(1, order.quantity());
                update.setString(2, order.sku());
                update.setInt(3, order.quantity());
                decision = update.executeUpdate() == 1 ? Decision.RESERVED : Decision.REJECTED;
            }
            try (var insert =
                    transaction.prepareStatement("INSERT INTO reservations (order_id, decision) VALUES (?, ?)")) {
                insert.setObject(1, order.orderId());
                insert.setString(2, decision.name());
                insert.executeUpdate();
            }
            // If a competing transaction reserved this order, the primary key fails and the stock
            // decrement rolls back with it. Kafka retries the bridge and reads the recorded result.
            var result = new ReservationDecided(order.orderId(), decision);
            JdbcOutbox.enqueue(transaction, Messages.RESULTS, order.orderId().toString(), result);
            return result;
        });
    }

    private static Decision recordedDecision(Connection connection, OrderPlaced order) throws SQLException {
        try (var query = connection.prepareStatement("SELECT decision FROM reservations WHERE order_id = ?")) {
            query.setObject(1, order.orderId());
            try (var rows = query.executeQuery()) {
                return rows.next() ? Decision.valueOf(rows.getString(1)) : null;
            }
        }
    }
}
