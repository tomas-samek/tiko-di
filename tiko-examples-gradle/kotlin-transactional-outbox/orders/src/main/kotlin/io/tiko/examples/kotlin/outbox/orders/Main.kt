package io.tiko.examples.kotlin.outbox.orders

import io.tiko.config.ConfigSources
import io.tiko.runtime.Tiko

/**
 * The container builds and starts everything: the database with its schema, the outbox relay, the
 * Kafka consumer and [OrdersHttpServer]. A failure there is thrown from here, and the process exits.
 * `Ctrl+C` / `SIGTERM` closes the container, which stops the server first.
 */
fun main() {
    Tiko.daemon(ConfigSources.classpath("orders.yaml")).awaitShutdown()
}
