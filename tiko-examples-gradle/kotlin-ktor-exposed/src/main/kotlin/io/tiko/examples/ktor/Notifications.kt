package io.tiko.examples.ktor

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.EventHandler

/** Where notifications go: a suspending call, as a mail or chat client's is. Tests replace it with a recording one. */
interface Notifier {
    suspend fun send(message: String)
}

@Component(scope = Scope.SINGLETON)
class LogNotifier : Notifier {
    override suspend fun send(message: String) {
        System.getLogger("io.tiko.examples.ktor").log(System.Logger.Level.INFO, message)
    }
}

/**
 * Reacts to [TicketOpened] on the event bus, after the ticket is stored.
 *
 * The handler is a `suspend fun`, so Tiko runs it as a coroutine of the container, off the request
 * that published the event and in a unit of work of its own. The request does not wait for it;
 * `close()` does.
 */
@Component(scope = Scope.SINGLETON)
class TicketNotifications(private val notifier: Notifier) {

    @EventHandler
    suspend fun onOpened(event: TicketOpened) {
        notifier.send("ticket ${event.ticket.id} opened by ${event.ticket.reporter} (request ${event.requestId})")
    }
}
