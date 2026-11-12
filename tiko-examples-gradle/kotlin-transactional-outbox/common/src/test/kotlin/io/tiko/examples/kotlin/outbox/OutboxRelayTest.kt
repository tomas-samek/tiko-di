package io.tiko.examples.kotlin.outbox

import java.time.Duration
import javax.sql.DataSource
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The relay drains a backlog at broker speed, keeps a row whose send fails for a later tick, and sends
 * nothing while the outbox is empty. It polls every 250 ms, so a 50-row backlog drained one row per poll
 * would take 12.5 s; the deadline below is far shorter.
 */
class OutboxRelayTest {
    private lateinit var database: DataSource
    private lateinit var producer: MockProducer<String, ByteArray>

    @BeforeEach
    fun openOutbox() {
        database = Database.open("jdbc:h2:mem:kotlin-outbox-relay-${System.nanoTime()};DB_CLOSE_DELAY=-1")
        Database.initialize(database, JdbcOutbox.SCHEMA)
        producer = MockProducer(true, StringSerializer(), ByteArraySerializer())
    }

    @Test
    fun backlogDrainsInOrderWellWithinOnePollPerRow() {
        val keys = (0 until 50).map { "order-$it" }
        enqueue(keys)

        OutboxRelay(database, producer).use {
            await().atMost(Duration.ofSeconds(2)).until { pendingRows() == 0L }
        }

        assertThat(sentKeys()).containsExactlyElementsOf(keys)
    }

    @Test
    fun failedSendKeepsTheRowAndNothingBehindItIsSentFirst() {
        val keys = listOf("first", "second", "third")
        enqueue(keys)
        producer.sendException = KafkaException("broker unavailable")

        OutboxRelay(database, producer).use {
            await().pollDelay(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2)).until { true }
            assertThat(producer.history()).isEmpty()
            assertThat(pendingRows()).isEqualTo(keys.size.toLong())

            producer.sendException = null
            await().atMost(Duration.ofSeconds(2)).until { pendingRows() == 0L }
        }

        assertThat(sentKeys()).containsExactlyElementsOf(keys)
    }

    @Test
    fun emptyOutboxSendsNothing() {
        OutboxRelay(database, producer).use {
            await().pollDelay(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2)).until { true }
        }

        assertThat(producer.history()).isEmpty()
    }

    private fun enqueue(keys: List<String>) {
        Database.transaction(database) { transaction ->
            keys.forEach { JdbcOutbox.enqueue(transaction, ORDERS, it, it) }
        }
    }

    private fun sentKeys(): List<String> = producer.history().map { it.key() }

    private fun pendingRows(): Long = database.connection.use { connection ->
        connection.createStatement().use { count ->
            count.executeQuery("SELECT COUNT(*) FROM outbox").use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }
}
