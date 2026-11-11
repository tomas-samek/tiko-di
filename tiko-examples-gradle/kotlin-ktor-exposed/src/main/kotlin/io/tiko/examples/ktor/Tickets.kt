package io.tiko.examples.ktor

import kotlinx.serialization.Serializable

@Serializable
data class Ticket(val id: String, val title: String, val reporter: String)

/** Request body of `POST /tickets`. */
@Serializable
data class OpenTicket(val title: String, val reporter: String)

/** Published after a ticket is stored; [requestId] names the HTTP request that opened it. */
data class TicketOpened(val ticket: Ticket, val requestId: String)
