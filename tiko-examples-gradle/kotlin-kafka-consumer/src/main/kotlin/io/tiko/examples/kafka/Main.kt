package io.tiko.examples.kafka

import io.tiko.config.ConfigSources
import io.tiko.runtime.Tiko
import java.util.concurrent.CountDownLatch

/** Boots the container (which starts the `payments` consumer) and runs until the JVM is asked to stop. */
fun main() {
    Tiko.create(ConfigSources.classpath("application.yaml")).use {
        val stop = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread(stop::countDown, "payments-shutdown"))
        println("payments-service consuming 'payments', producing 'receipts' (Ctrl+C stops it)")
        stop.await()
    }
}
