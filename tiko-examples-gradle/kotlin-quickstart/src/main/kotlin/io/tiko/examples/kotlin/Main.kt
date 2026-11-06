package io.tiko.examples.kotlin

import io.tiko.runtime.Tiko
import kotlin.system.exitProcess

private val EXPECTED = listOf("OrderPlaced", "OrderValidated")

/** Boots the container, publishes one order, and returns the recorded event chain. */
fun runQuickstart(): List<String> =
    Tiko.create().use { container ->
        container.runInEventScope { container.eventBus.publish(OrderPlaced("A1")) }
        container.get(EventLog::class.java).chain()
    }

fun main() {
    val chain = runQuickstart()
    println("event chain: $chain")
    if (chain != EXPECTED) {
        System.err.println("expected $EXPECTED but got $chain")
        exitProcess(1)
    }
}
