package io.tiko.examples.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.tiko.UnitOfWork
import io.tiko.coroutines.inEventScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class ErrorBody(val error: String)

/**
 * The Ktor module. Each route runs its work in one unit of work with [inEventScope]: the unit
 * lasts across suspension, so the EVENT-scoped `RequestId` the service reads on the IO dispatcher
 * is the one the route reads after it.
 *
 * The service is blocking code (JDBC), so the routes call it on [Dispatchers.IO], inside the unit.
 *
 * @param unitOfWork opens a request's unit of work; `main` passes the container, which is one
 */
fun Application.ticketModule(unitOfWork: UnitOfWork, tickets: TicketService) {
    install(ContentNegotiation) { json() }
    // Ktor lets a route run to its end when its client disconnects, unless told otherwise. With
    // this, the route's coroutine is cancelled, and the request's unit of work closes with it.
    install(HttpRequestLifecycle) { cancelCallOnClose = true }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "bad request"))
        }
    }

    routing {
        post("/tickets") {
            val request = call.receive<OpenTicket>()
            val (ticket, requestId) = unitOfWork.inEventScope {
                val opened = withContext(Dispatchers.IO) { tickets.open(request) }
                // Back on the route's dispatcher, still in the request's unit of work.
                opened to tickets.requestId()
            }
            call.response.header("X-Request-Id", requestId)
            call.respond(HttpStatusCode.Created, ticket)
        }
        // Parallel work in a request. Each async is concurrent work, so it runs in a unit of
        // work of its own, with its own RequestId: it is handed the reporter it counts, and
        // takes nothing from the request's unit.
        get("/tickets/summary") {
            val summary = unitOfWork.inEventScope {
                val reporters = withContext(Dispatchers.IO) { tickets.reporters() }
                val counts = reporters.map { reporter -> async(Dispatchers.IO) { tickets.ticketsOf(reporter) } }
                TicketSummary(tickets.requestId(), counts.awaitAll())
            }
            call.respond(summary)
        }
        get("/tickets/{id}") {
            val id = call.parameters["id"].orEmpty()
            val ticket = unitOfWork.inEventScope { withContext(Dispatchers.IO) { tickets.find(id) } }
            if (ticket == null) call.respond(HttpStatusCode.NotFound) else call.respond(ticket)
        }
        get("/tickets") {
            call.respond(unitOfWork.inEventScope { withContext(Dispatchers.IO) { tickets.all() } })
        }
    }
}
