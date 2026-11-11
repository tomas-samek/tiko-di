package io.tiko.examples.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.tiko.config.ConfigSources
import io.tiko.runtime.Tiko
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Boots the container, then serves the API on `http.port` until the JVM is asked to stop.
 * With `--smoke`, it opens one ticket over HTTP, lists the tickets, and exits (1 on failure).
 */
fun main(args: Array<String>) {
    val ok =
        Tiko.create(ConfigSources.classpath("application.yaml")).use { container ->
            val port = container.get(HttpConfig::class.java).port
            val server = embeddedServer(CIO, port = port) { ticketModule(container) }.start(wait = false)
            try {
                if ("--smoke" in args) {
                    smokeTest(port)
                } else {
                    println("ticket API on http://localhost:$port/tickets (Ctrl+C stops it)")
                    val stop = CountDownLatch(1)
                    Runtime.getRuntime().addShutdownHook(Thread(stop::countDown, "ticket-api-shutdown"))
                    stop.await()
                    true
                }
            } finally {
                server.stop()
            }
        }
    if (!ok) exitProcess(1)
}

private fun smokeTest(port: Int): Boolean {
    val http = HttpClient.newHttpClient()
    val created =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/tickets"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"title":"Printer on fire","reporter":"alice"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    val listed =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/tickets")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    println("POST /tickets -> ${created.statusCode()} ${created.body()}")
    println("GET  /tickets -> ${listed.statusCode()} ${listed.body()}")
    val ok = created.statusCode() == 201 && listed.body().contains("Printer on fire")
    if (!ok) System.err.println("smoke test failed")
    return ok
}
