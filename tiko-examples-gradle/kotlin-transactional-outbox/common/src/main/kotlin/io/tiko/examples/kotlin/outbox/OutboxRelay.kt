package io.tiko.examples.kotlin.outbox

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer

/** Relays [database]'s outbox through [producer]; tests pass a `MockProducer`. */
class OutboxRelay(database: DataSource, private val producer: Producer<String, ByteArray>) : AutoCloseable {
    constructor(database: DataSource, bootstrap: String) : this(database, connect(bootstrap))

    private val outbox = JdbcOutbox(database)
    private val worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("outbox-relay").factory())

    init {
        worker.scheduleWithFixedDelay(::drainOutbox, 0, 250, TimeUnit.MILLISECONDS)
    }

    private fun drainOutbox() {
        try {
            while (!Thread.currentThread().isInterrupted && outbox.publishNext { producer.send(it) }) {
                // Continue until the outbox is empty.
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            log.log(System.Logger.Level.WARNING, "Outbox publish failed. The pending row will be retried.", failure)
        }
    }

    override fun close() {
        worker.shutdownNow()
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                log.log(System.Logger.Level.WARNING, "Outbox relay did not stop within the shutdown deadline.")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            producer.close(Duration.ofSeconds(5))
        }
    }

    companion object {
        private val log = System.getLogger(OutboxRelay::class.java.name)

        private fun connect(bootstrap: String): Producer<String, ByteArray> = KafkaProducer(
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 5000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 3000,
                ProducerConfig.MAX_BLOCK_MS_CONFIG to 2000,
            ),
            StringSerializer(),
            ByteArraySerializer(),
        )
    }
}
