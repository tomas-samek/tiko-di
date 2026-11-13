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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.tiko.Container
import io.tiko.config.ConfigSources
import io.tiko.runtime.TikoOptions
import io.tiko.test.RecordingEventBus
import io.tiko.test.TikoTest
import io.tiko.test.TikoTestOptions
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
        application { ticketModule(container) }
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
        assertThat((notifier as RecordingNotifier).sent).singleElement().asString().contains(ticket.id, "alice")
    }

    @Test
    fun blankTitleIsABadRequest(container: Container) = testApplication {
        application { ticketModule(container) }

        val response =
            jsonClient().post("/tickets") {
                contentType(ContentType.Application.Json)
                setBody(OpenTicket(" ", "bob"))
            }

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun unknownTicketIsNotFound(container: Container) = testApplication {
        application { ticketModule(container) }

        assertThat(client.get("/tickets/missing").status).isEqualTo(HttpStatusCode.NotFound)
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }
}
