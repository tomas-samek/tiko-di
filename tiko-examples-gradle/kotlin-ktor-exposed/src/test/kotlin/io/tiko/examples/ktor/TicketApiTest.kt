package io.tiko.examples.ktor

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.tiko.Container
import io.tiko.config.ConfigSources
import io.tiko.coroutines.inEventScope
import io.tiko.events.EventEndingEvent
import io.tiko.events.EventStartedEvent
import io.tiko.runtime.TikoOptions
import io.tiko.test.RecordingEventBus
import io.tiko.test.TikoTest
import io.tiko.test.TikoTestOptions
import java.net.Socket
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Drives the real routes in Ktor's test engine against a `@TikoTest` container. */
@TikoTest
class TicketApiTest {

    /** The test configuration: a fresh in-memory database per test and an ephemeral port. */
    @TikoTestOptions
    fun options(options: TikoOptions.Builder) {
        options.configSource(ConfigSources.classpath("application-test.yaml"))
    }

    @Test
    fun openedTicketIsStoredAndAnnounced(
        container: Container,
        bus: RecordingEventBus,
        notifier: Notifier,
    ) = testApplication {
        application { ticketModule(container, container.get(TicketService::class.java)) }
        val client = jsonClient()

        val created =
            client.post("/tickets") {
                contentType(ContentType.Application.Json)
                setBody(OpenTicket("Printer on fire", "alice"))
            }
        assertThat(created.status).isEqualTo(HttpStatusCode.Created)
        val ticket = created.body<Ticket>()
        val requestId = created.headers["X-Request-Id"]

        assertThat(client.get("/tickets/${ticket.id}").body<Ticket>()).isEqualTo(ticket)
        bus.assertPublished(TicketOpened::class.java)
            .withPayload { e: TicketOpened -> e.ticket == ticket && e.requestId == requestId }
        // The notification comes from a suspend handler: it runs off the request, so wait for it.
        bus.awaitAsyncDispatch(Duration.ofSeconds(10))
        assertThat((notifier as RecordingNotifier).sent).singleElement().asString().contains(ticket.id, "alice")
    }

    @Test
    fun blankTitleIsABadRequest(container: Container) = testApplication {
        application { ticketModule(container, container.get(TicketService::class.java)) }

        val response =
            jsonClient().post("/tickets") {
                contentType(ContentType.Application.Json)
                setBody(OpenTicket(" ", "bob"))
            }

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun unknownTicketIsNotFound(container: Container) = testApplication {
        application { ticketModule(container, container.get(TicketService::class.java)) }

        assertThat(client.get("/tickets/missing").status).isEqualTo(HttpStatusCode.NotFound)
    }

    /** Each request is a unit of work of its own, also when two of them are suspended at the same time. */
    @Test
    fun twoConcurrentRequestsGetDifferentUnits(container: Container, bus: RecordingEventBus) = testApplication {
        application { ticketModule(container, container.get(TicketService::class.java)) }
        val client = jsonClient()

        val created = coroutineScope {
            listOf("alice", "bob").map { reporter ->
                async {
                    client.post("/tickets") {
                        contentType(ContentType.Application.Json)
                        setBody(OpenTicket("Printer on fire", reporter))
                    }
                }
            }.awaitAll()
        }

        val requestIds = created.map { it.headers["X-Request-Id"] }
        assertThat(requestIds.toSet()).`as`("one request id per request").hasSize(2).doesNotContainNull()
        val opened = bus.events(TicketOpened::class.java)
        created.forEach { response ->
            val ticket = response.body<Ticket>()
            assertThat(opened.single { it.ticket == ticket }.requestId)
                .`as`("the id the service saw on the IO dispatcher is the one the route read after it")
                .isEqualTo(response.headers["X-Request-Id"])
        }
    }

    /** `map { async { ... } }` in a request: every element is concurrent work, so a unit of its own. */
    @Test
    fun parallelWorkInARequestRunsInUnitsOfItsOwn(container: Container) = testApplication {
        application { ticketModule(container, container.get(TicketService::class.java)) }
        val client = jsonClient()
        listOf("carol" to "Printer on fire", "carol" to "Paper jam", "dave" to "No coffee").forEach { (reporter, title) ->
            client.post("/tickets") {
                contentType(ContentType.Application.Json)
                setBody(OpenTicket(title, reporter))
            }
        }

        val summary = client.get("/tickets/summary").body<TicketSummary>()

        // The tests of this class share one database, so other reporters may be in the summary too.
        assertThat(summary.reporters.map { it.reporter to it.tickets }).contains("carol" to 2, "dave" to 1)
        assertThat((summary.reporters.map { it.countedIn } + summary.requestId).toSet())
            .`as`("the request and each reporter's count ran in a unit of work of their own")
            .hasSize(summary.reporters.size + 1)
    }

    /**
     * A client that goes away cancels the route's coroutine (the module installs
     * `HttpRequestLifecycle` with `cancelCallOnClose`), and the request's unit of work closes.
     * Ktor's test engine has no connection to close, so this one runs the CIO engine and hangs up
     * a real socket in the middle of the request.
     */
    @Test
    fun aRequestWhoseClientGoesAwayClosesItsUnit(container: Container, bus: RecordingEventBus) = runBlocking {
        val inTheUnit = CompletableDeferred<Unit>()
        val server =
            embeddedServer(CIO, port = 0) {
                ticketModule(container, container.get(TicketService::class.java))
                routing {
                    get("/slow") {
                        container.inEventScope {
                            inTheUnit.complete(Unit)
                            delay(60_000)
                        }
                    }
                }
            }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val started =
                Socket("localhost", port).use { client ->
                    client.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    client.getOutputStream().flush()
                    withTimeout(10_000) { inTheUnit.await() }
                    bus.events(EventStartedEvent::class.java).last().eventId()
                } // the client hangs up here

            withTimeout(10_000) {
                while (bus.events(EventEndingEvent::class.java).none { it.eventId() == started }) {
                    delay(10)
                }
            }
        } finally {
            server.stop(100, 1_000)
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }
}
