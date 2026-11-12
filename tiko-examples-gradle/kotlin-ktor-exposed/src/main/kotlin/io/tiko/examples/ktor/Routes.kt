package io.tiko.examples.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.tiko.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class ErrorBody(val error: String)

/**
 * The Ktor module. It takes [TicketService] from the container once and runs each request's
 * work in [unitOfWork].
 */
fun Application.ticketModule(container: Container) {
    val tickets = container.get(TicketService::class.java)

    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "bad request"))
        }
    }

    routing {
        post("/tickets") {
            val request = call.receive<OpenTicket>()
            val (ticket, requestId) = container.unitOfWork { tickets.open(request) to tickets.requestId() }
            call.response.header("X-Request-Id", requestId)
            call.respond(HttpStatusCode.Created, ticket)
        }
        get("/tickets/{id}") {
            val id = call.parameters["id"].orEmpty()
            val ticket = container.unitOfWork { tickets.find(id) }
            if (ticket == null) call.respond(HttpStatusCode.NotFound) else call.respond(ticket)
        }
        get("/tickets") {
            call.respond(container.unitOfWork { tickets.all() })
        }
    }
}

/**
 * Runs [block] as one Tiko unit of work (an EVENT scope) on the IO dispatcher.
 *
 * Tiko's EVENT scope is bound to the thread, and [block] is blocking code (JDBC), so [block]
 * does not suspend: read the request before calling this, and respond after it returns.
 */
suspend fun <T> Container.unitOfWork(block: () -> T): T =
    withContext(Dispatchers.IO) { supplyInEventScope(block) }
