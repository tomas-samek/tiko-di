package io.tiko.examples.kotlin.outbox.orders

import io.tiko.config.ConfigSources
import io.tiko.runtime.Tiko

fun main() {
    val daemon = Tiko.daemon(ConfigSources.classpath("orders.yaml"))
    val container = daemon.container()
    try {
        container.get(OrdersHttpServer::class.java).start(container)
    } catch (failure: Throwable) {
        // A failed HTTP start must also stop the relay and Kafka consumer.
        runCatching { daemon.stop() }.exceptionOrNull()?.let { cleanupFailure ->
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    daemon.awaitShutdown()
}
