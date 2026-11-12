package io.tiko.examples.kotlin.outbox.e2e

import io.tiko.examples.kotlin.outbox.Database
import io.tiko.examples.kotlin.outbox.ORDERS
import io.tiko.examples.kotlin.outbox.OrderPlaced
import io.tiko.examples.kotlin.outbox.RESULTS
import io.tiko.kafka.serializer.JsonKafkaSerializer
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

class OutboxRecoveryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun recoversCommittedOrderAfterProcessDiesAndDeduplicatesReplay() {
        val dockerAvailable = DockerClientFactory.instance().isDockerAvailable
        if (java.lang.Boolean.getBoolean("outbox.requireDocker")) {
            assertTrue(dockerAvailable, "Docker is required for the outbox recovery test")
        }
        assumeTrue(dockerAvailable, "Docker is unavailable")

        KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.2")).withStartupAttempts(3).use { kafka ->
            kafka.start()
            verifyRecovery(kafka.bootstrapServers)
        }
    }

    private fun verifyRecovery(bootstrap: String) {
        val orderDb = jdbcUrl("orders")
        val warehouseDb = jdbcUrl("warehouse")
        val orderId = UUID.randomUUID()
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

        http.use {
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                admin.createTopics(listOf(NewTopic(ORDERS, 1, 1), NewTopic(RESULTS, 1, 1)))
                    .all().get(TIMEOUT.seconds, TimeUnit.SECONDS)

                start("orders", "orders-disconnected", orderDb, "127.0.0.1:1").use { orders ->
                    val accepted = send(http, HttpRequest.newBuilder(orders.orderUri(orderId))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""{"sku":"widget","quantity":2}""")))
                    assertEquals(202, accepted.statusCode())
                    assertTrue(accepted.body().contains("PENDING"), accepted.body())
                    // The HTTP response proves the commit completed. Kill without running shutdown hooks.
                    orders.kill()
                }
                assertEquals(1, scalar(orderDb, "SELECT COUNT(*) FROM orders WHERE status = 'PENDING'"))
                assertEquals(1, scalar(orderDb, "SELECT COUNT(*) FROM outbox"))

                start("warehouse", "warehouse", warehouseDb, bootstrap).use {
                    start("orders", "orders-restarted", orderDb, bootstrap).use { orders ->
                        val uri = orders.orderUri(orderId)
                        await().atMost(TIMEOUT).ignoreExceptionsInstanceOf(IOException::class.java).untilAsserted {
                            val response = send(http, HttpRequest.newBuilder(uri).GET())
                            assertEquals(200, response.statusCode())
                            assertTrue(response.body().contains("RESERVED"), response.body())
                        }
                        val replayOffset = publishDuplicate(bootstrap, OrderPlaced(orderId, "widget", 2))
                        await().atMost(TIMEOUT).untilAsserted {
                            val offsets = admin.listConsumerGroupOffsets("kotlin-outbox-warehouse")
                                .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                            val offset = offsets[TopicPartition(ORDERS, 0)]?.offset() ?: -1
                            assertTrue(offset > replayOffset, "Kafka has not committed the replayed order")
                        }
                    }
                }
                assertEquals(8, scalar(warehouseDb, "SELECT available FROM stock WHERE sku = 'widget'"))
                assertEquals(1, scalar(warehouseDb, "SELECT COUNT(*) FROM reservations"))
            }
        }
    }

    private fun jdbcUrl(service: String) = "jdbc:h2:file:" + directory.resolve(service) + ";WRITE_DELAY=0"

    private fun start(service: String, name: String, url: String, bootstrap: String): Service {
        val lib = System.getProperty("$service.lib")
        val log = directory.resolve("$name.log")
        val command = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", lib + File.separator + "*", "io.tiko.examples.kotlin.outbox.$service.MainKt",
        ).redirectErrorStream(true).redirectOutput(log.toFile())
        command.environment().apply {
            put("KAFKA_BOOTSTRAP", bootstrap)
            put(if (service == "orders") "ORDERS_DB_URL" else "WAREHOUSE_DB_URL", url)
            put("ORDERS_HTTP_PORT", "0")
        }
        return Service(command.start(), log)
    }

    private fun send(http: HttpClient, request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())

    private fun publishDuplicate(bootstrap: String, order: OrderPlaced): Long = KafkaProducer(
        mapOf<String, Any>("bootstrap.servers" to bootstrap, "max.block.ms" to 5000),
        StringSerializer(),
        ByteArraySerializer(),
    ).use { producer ->
        val record = ProducerRecord(ORDERS, order.orderId.toString(), JsonKafkaSerializer().serialize(order))
        producer.send(record).get(10, TimeUnit.SECONDS).offset()
    }

    private fun scalar(url: String, sql: String): Int = Database.open(url).connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next()) { "Query returned no rows: $sql" }
                rows.getInt(1)
            }
        }
    }

    private class Service(private val process: Process, private val log: Path) : AutoCloseable {
        fun orderUri(id: UUID): URI {
            val port = await().atMost(TIMEOUT).until({
                val output = Files.readString(log)
                check(process.isAlive) { "Orders process exited. Log:\n$output" }
                output.lineSequence().firstOrNull { it.startsWith(PORT_LINE) }?.removePrefix(PORT_LINE)
            }, { it != null })
            return URI.create("http://127.0.0.1:$port/orders/$id")
        }

        fun kill() {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Service process did not stop")
            }
        }

        override fun close() {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(15, TimeUnit.SECONDS)) kill()
            }
        }
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(60)
        private const val PORT_LINE = "outbox-orders HTTP port "
    }
}
