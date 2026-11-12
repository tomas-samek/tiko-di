package io.tiko.examples.ktor

import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.EventHandler

/** Where notifications go. Tests replace it with a recording one. */
interface Notifier {
    fun send(message: String)
}

@Component(scope = Scope.SINGLETON)
class LogNotifier : Notifier {
    override fun send(message: String) {
        System.getLogger("io.tiko.examples.ktor").log(System.Logger.Level.INFO, message)
    }
}

/** Reacts to [TicketOpened] on the event bus, after the ticket is stored. */
@Component(scope = Scope.SINGLETON)
class TicketNotifications(private val notifier: Notifier) {

    @EventHandler
    fun onOpened(event: TicketOpened) {
        notifier.send("ticket ${event.ticket.id} opened by ${event.ticket.reporter} (request ${event.requestId})")
    }
}
