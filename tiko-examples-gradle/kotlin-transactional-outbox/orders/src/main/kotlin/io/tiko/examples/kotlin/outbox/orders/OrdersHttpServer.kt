package io.tiko.examples.kotlin.outbox.orders

import io.javalin.Javalin
import io.javalin.http.BadRequestResponse
import io.javalin.http.ConflictResponse
import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.NotFoundResponse
import io.tiko.Scope
import io.tiko.UnitOfWork
import io.tiko.annotations.Component
import io.tiko.annotations.EventHandler
import io.tiko.annotations.PostConstruct
import io.tiko.annotations.PreDestroy
import io.tiko.events.ApplicationEndingEvent
import io.tiko.examples.kotlin.outbox.OrderPlaced
import io.tiko.examples.kotlin.outbox.OutboxRelay
import java.util.UUID

/**
 * The orders HTTP API, owned by the container. The container builds it when it starts, and [start]
 * binds the port then: a port already in use fails `Tiko.daemon(...)`. It takes the [OutboxRelay]
 * only so that the relay runs from boot, and it stops on [ApplicationEndingEvent], before the
 * container closes the relay.
 */
private const val STOP_TIMEOUT_MS = 5_000L

@Component(scope = Scope.SINGLETON)
class OrdersHttpServer(
    private val unitOfWork: UnitOfWork,
    private val orders: OrderStore,
    private val settings: Settings,
    @Suppress("UNUSED_PARAMETER") relay: OutboxRelay,
) {
    @Volatile
    private var app: Javalin? = null

    @PostConstruct
    fun start() {
        val server = Javalin.create { config ->
            // Lets a request in flight at stop() finish and get its response; without it, Javalin
            // 7.2.3 runs the handler to its end but closes the connection before the response.
            config.jetty.modifyServer { it.stopTimeout = STOP_TIMEOUT_MS }
            config.routes.put("/orders/{id}", inUnit { ctx -> place(orders, ctx) })
            config.routes.get("/orders/{id}", inUnit { ctx ->
                ctx.json(orders.find(orderId(ctx)) ?: throw NotFoundResponse())
            })
        }.start(settings.httpHost, settings.httpPort)
        app = server
        println("outbox-orders HTTP port " + server.port())
    }

    @EventHandler
    fun onApplicationEnding(@Suppress("UNUSED_PARAMETER") event: ApplicationEndingEvent) = stop()

    @PreDestroy
    fun stop() {
        val running = app
        app = null
        running?.stop()
    }

    /** One unit of work per request. */
    private fun inUnit(handle: (Context) -> Unit): Handler = Handler { ctx ->
        unitOfWork.runInEventScope { handle(ctx) }
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

@JvmRecord
data class OrderRequest(val sku: String?, val quantity: Int)
