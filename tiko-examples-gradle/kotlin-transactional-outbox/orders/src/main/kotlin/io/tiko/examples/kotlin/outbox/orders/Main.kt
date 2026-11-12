package io.tiko.examples.kotlin.outbox.orders

import io.javalin.Javalin
import io.javalin.http.BadRequestResponse
import io.javalin.http.ConflictResponse
import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.NotFoundResponse
import io.tiko.Container
import io.tiko.config.ConfigSources
import io.tiko.examples.kotlin.outbox.OrderPlaced
import io.tiko.examples.kotlin.outbox.OutboxRelay
import io.tiko.runtime.Tiko
import java.util.UUID

fun main() {
    val container = Tiko.create(ConfigSources.classpath("orders.yaml"))
    try {
        val orders = container.get(OrderStore::class.java)
        val app = Javalin.create { config ->
            config.routes.put("/orders/{id}", scoped(container) { ctx -> place(orders, ctx) })
            config.routes.get("/orders/{id}", scoped(container) { ctx ->
                ctx.json(orders.find(orderId(ctx)) ?: throw NotFoundResponse())
            })
        }
        Runtime.getRuntime().addShutdownHook(Thread({
            try {
                app.stop()
            } finally {
                container.close()
            }
        }, "outbox-orders-shutdown"))
        container.get(OutboxRelay::class.java)
        val settings = container.get(Settings::class.java)
        app.start(settings.httpHost, settings.httpPort)
        println("outbox-orders HTTP port " + app.port())
    } catch (failure: Throwable) {
        container.close()
        throw failure
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
