package io.tiko.examples.kotlin.outbox.orders

import io.javalin.Javalin
import io.javalin.http.BadRequestResponse
import io.javalin.http.ConflictResponse
import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.NotFoundResponse
import io.tiko.Container
import io.tiko.Provider
import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.examples.kotlin.outbox.OrderPlaced
import io.tiko.examples.kotlin.outbox.OutboxRelay
import java.util.UUID

@Component(scope = Scope.SINGLETON)
class OrdersHttpServer(
    private val orders: OrderStore,
    private val settings: Settings,
    private val relay: Provider<OutboxRelay>,
) : AutoCloseable {
    private var app: Javalin? = null

    fun start(container: Container) {
        // Start the relay after the order store has initialized the schema.
        relay.get()
        val server = Javalin.create { config ->
            config.routes.put("/orders/{id}", scoped(container) { ctx -> place(orders, ctx) })
            config.routes.get("/orders/{id}", scoped(container) { ctx ->
                ctx.json(orders.find(orderId(ctx)) ?: throw NotFoundResponse())
            })
        }
        app = server.start(settings.httpHost, settings.httpPort)
        println("outbox-orders HTTP port " + server.port())
    }

    override fun close() {
        app?.stop()
    }
}

private fun place(orders: OrderStore, ctx: Context) {
    val id = orderId(ctx)
    val request = ctx.bodyAsClass(OrderRequest::class.java)
    val order = try {
        OrderPlaced(id, requireNotNull(request.sku) { "A SKU is required" }, request.quantity)
    } catch (invalid: IllegalArgumentException) {
        throw BadRequestResponse(invalid.message ?: "Invalid order")
    }
    ctx.status(202).json(orders.place(order) ?: throw ConflictResponse("Order ID already used"))
}

private fun orderId(ctx: Context): UUID = try {
    UUID.fromString(ctx.pathParam("id"))
} catch (_: IllegalArgumentException) {
    throw BadRequestResponse("Order ID must be a UUID")
}

private fun scoped(container: Container, handle: (Context) -> Unit): Handler = Handler { ctx ->
    container.runInEventScope(Runnable { handle(ctx) })
}

@JvmRecord
data class OrderRequest(val sku: String?, val quantity: Int)
