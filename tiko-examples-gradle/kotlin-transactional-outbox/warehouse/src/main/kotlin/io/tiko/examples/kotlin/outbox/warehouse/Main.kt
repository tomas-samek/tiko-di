package io.tiko.examples.kotlin.outbox.warehouse

import io.tiko.config.ConfigSources
import io.tiko.examples.kotlin.outbox.OutboxRelay
import io.tiko.runtime.Tiko

fun main() {
    val daemon = Tiko.daemon(ConfigSources.classpath("warehouse.yaml"))
    try {
        daemon.container().get(WarehouseStore::class.java)
        daemon.container().get(OutboxRelay::class.java)
        println("outbox-warehouse ready")
        daemon.awaitShutdown()
    } finally {
        daemon.stop()
    }
}
