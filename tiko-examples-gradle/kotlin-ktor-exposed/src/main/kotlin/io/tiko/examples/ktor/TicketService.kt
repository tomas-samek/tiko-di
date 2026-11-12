package io.tiko.examples.ktor

import io.tiko.EventBus
import io.tiko.Scope
import io.tiko.annotations.Component
import java.util.UUID

/** The business logic. Knows nothing about HTTP; Ktor routes call it. */
@Component(scope = Scope.SINGLETON)
class TicketService(
    private val repository: TicketRepository,
    private val events: EventBus,
    private val requestId: RequestId,
) {
    fun open(request: OpenTicket): Ticket {
        require(request.title.isNotBlank()) { "title must not be blank" }
        val ticket = Ticket(UUID.randomUUID().toString(), request.title.trim(), request.reporter)
        repository.save(ticket)
        events.publish(TicketOpened(ticket, requestId.value))
        return ticket
    }

    fun find(id: String): Ticket? = repository.find(id)

    fun all(): List<Ticket> = repository.all()

    /** The id of the request this call runs in. */
    fun requestId(): String = requestId.value
}
