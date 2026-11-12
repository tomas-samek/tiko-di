package io.tiko.examples.kotlin.outbox.orders

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.PostConstruct
import io.tiko.examples.kotlin.outbox.Database
import io.tiko.examples.kotlin.outbox.JdbcOutbox
import io.tiko.examples.kotlin.outbox.ORDERS
import io.tiko.examples.kotlin.outbox.OrderPlaced
import io.tiko.examples.kotlin.outbox.ReservationDecided
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@Component(scope = Scope.SINGLETON)
class OrderStore(private val database: DataSource) {
    @PostConstruct
    @Throws(SQLException::class)
    fun initialize() = Database.initialize(database, JdbcOutbox.SCHEMA, """
        CREATE TABLE IF NOT EXISTS orders (
            order_id UUID PRIMARY KEY,
            sku VARCHAR(40) NOT NULL,
            quantity INTEGER NOT NULL CHECK (quantity > 0),
            status VARCHAR(12) NOT NULL
        )
    """.trimIndent())

    fun place(order: OrderPlaced): Order? = try {
        Database.transaction(database) { transaction ->
            transaction.prepareStatement(
                "INSERT INTO orders (order_id, sku, quantity, status) VALUES (?, ?, ?, 'PENDING')",
            ).use { insert ->
                insert.setObject(1, order.orderId)
                insert.setString(2, order.sku)
                insert.setInt(3, order.quantity)
                insert.executeUpdate()
            }
            JdbcOutbox.enqueue(transaction, ORDERS, order.orderId.toString(), order)
            Order(order.orderId, order.sku, order.quantity, "PENDING")
        }
    } catch (failure: SQLException) {
        if (failure.sqlState != "23505") throw failure
        // A concurrent HTTP retry can win the primary-key insert.
        val saved = find(order.orderId) ?: throw failure
        saved.takeIf { it.sku == order.sku && it.quantity == order.quantity }
    }

    fun find(orderId: UUID): Order? = database.connection.use { find(it, orderId) }

    fun record(result: ReservationDecided): Boolean = Database.transaction(database) { transaction ->
        transaction.prepareStatement(
            "UPDATE orders SET status = ? WHERE order_id = ? AND status = 'PENDING'",
        ).use { update ->
            update.setString(1, result.decision.name)
            update.setObject(2, result.orderId)
            update.executeUpdate() > 0 || find(transaction, result.orderId)?.status == result.decision.name
        }
    }

    private fun find(connection: Connection, orderId: UUID): Order? = connection.prepareStatement(
        "SELECT sku, quantity, status FROM orders WHERE order_id = ?",
    ).use { query ->
        query.setObject(1, orderId)
        query.executeQuery().use { rows ->
            if (rows.next()) Order(orderId, rows.getString(1), rows.getInt(2), rows.getString(3)) else null
        }
    }
}

@JvmRecord
data class Order(val orderId: UUID, val sku: String, val quantity: Int, val status: String)
