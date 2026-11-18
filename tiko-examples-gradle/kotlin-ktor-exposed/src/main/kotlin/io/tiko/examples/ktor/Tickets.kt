package io.tiko.examples.ktor

import kotlinx.serialization.Serializable

@Serializable
data class Ticket(val id: String, val title: String, val reporter: String)

/** Request body of `POST /tickets`. */
@Serializable
data class OpenTicket(val title: String, val reporter: String)

/** Response of `GET /tickets/summary`; [requestId] names the HTTP request that asked. */
@Serializable
data class TicketSummary(val requestId: String, val reporters: List<ReporterTickets>)

/**
 * How many tickets [reporter] has opened. [countedIn] names the unit of work that counted them:
 * the summary counts each reporter concurrently, and concurrent work is a unit of its own.
 */
@Serializable
data class ReporterTickets(val reporter: String, val tickets: Int, val countedIn: String)

/** Published after a ticket is stored; [requestId] names the HTTP request that opened it. */
data class TicketOpened(val ticket: Ticket, val requestId: String)
