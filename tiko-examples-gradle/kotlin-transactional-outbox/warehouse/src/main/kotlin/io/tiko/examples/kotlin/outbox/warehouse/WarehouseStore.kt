package io.tiko.examples.kotlin.outbox.warehouse

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.PostConstruct
import io.tiko.examples.kotlin.outbox.Database
import io.tiko.examples.kotlin.outbox.Decision
import io.tiko.examples.kotlin.outbox.JdbcOutbox
import io.tiko.examples.kotlin.outbox.OrderPlaced
import io.tiko.examples.kotlin.outbox.RESULTS
import io.tiko.examples.kotlin.outbox.ReservationDecided
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@Component(scope = Scope.SINGLETON)
class WarehouseStore(private val database: DataSource) {
    @PostConstruct
    @Throws(SQLException::class)
    fun initialize() = Database.initialize(database, JdbcOutbox.SCHEMA, """
        CREATE TABLE IF NOT EXISTS stock (
            sku VARCHAR(40) PRIMARY KEY,
            available INTEGER NOT NULL CHECK (available >= 0)
        )
    """.trimIndent(), """
        CREATE TABLE IF NOT EXISTS reservations (
            order_id UUID PRIMARY KEY,
            decision VARCHAR(12) NOT NULL
        )
    """.trimIndent(), """
        INSERT INTO stock (sku, available)
        SELECT 'widget', 10 WHERE NOT EXISTS (SELECT 1 FROM stock WHERE sku = 'widget')
    """.trimIndent())

    fun reserve(order: OrderPlaced): ReservationDecided = Database.transaction(database) { transaction ->
        val existing = recordedDecision(transaction, order.orderId)
        if (existing != null) return@transaction ReservationDecided(order.orderId, existing)

        val decision = transaction.prepareStatement(
            "UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?",
        ).use { update ->
            update.setInt(1, order.quantity)
            update.setString(2, order.sku)
            update.setInt(3, order.quantity)
            if (update.executeUpdate() == 1) Decision.RESERVED else Decision.REJECTED
        }
        // If a competing delivery inserted this ID, the primary-key violation rolls back the stock change.
        transaction.prepareStatement("INSERT INTO reservations (order_id, decision) VALUES (?, ?)").use { insert ->
            insert.setObject(1, order.orderId)
            insert.setString(2, decision.name)
            insert.executeUpdate()
        }
        val result = ReservationDecided(order.orderId, decision)
        JdbcOutbox.enqueue(transaction, RESULTS, order.orderId.toString(), result)
        result
    }

    private fun recordedDecision(connection: Connection, orderId: UUID): Decision? = connection.prepareStatement(
        "SELECT decision FROM reservations WHERE order_id = ?",
    ).use { query ->
        query.setObject(1, orderId)
        query.executeQuery().use { rows -> if (rows.next()) Decision.valueOf(rows.getString(1)) else null }
    }
}
