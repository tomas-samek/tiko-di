package io.tiko.examples.kafka

import io.tiko.config.ConfigSources
import io.tiko.kafka.KafkaTransport
import io.tiko.kafka.serializer.JsonKafkaSerializer
import io.tiko.kafka.test.FakeKafkaBroker
import io.tiko.kafka.test.FakeKafkaTransport
import io.tiko.runtime.TikoOptions
import io.tiko.test.TikoTest
import io.tiko.test.TikoTestOptions
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test

/**
 * The whole flow against an in-memory broker: no Kafka, no Docker. `@TikoTestOptions` gives the
 * test's container the app's config and swaps the Kafka transport for one over [broker]. The test
 * fails if a consumer thread is still running after its container closes.
 */
@TikoTest
class PaymentFlowTest {

    private val json = JsonKafkaSerializer()
    private val broker = FakeKafkaBroker()

    @TikoTestOptions
    fun options(options: TikoOptions.Builder) {
        options
            .configSource(ConfigSources.classpath("application.yaml"))
            .replaceTransport(KafkaTransport::class.java) { FakeKafkaTransport.over(it, broker) }
    }

    @Test
    fun paymentIsRecordedAndReceiptedOnce(ledger: Ledger) {
        val first = PaymentReceived("pay-1", "order-7", 4200)
        val second = PaymentReceived("pay-2", "order-7", 800)

        broker.produce("payments", json.serialize(first))
        broker.produce("payments", json.serialize(first)) // redelivered
        broker.produce("payments", json.serialize(second))

        // Records are consumed in order, so once pay-2's receipt is out the duplicate was handled.
        await atMost Duration.ofSeconds(10) untilAsserted {
            assertThat(broker.produced("receipts")).hasSize(2)
        }
        val receipts = broker.produced("receipts")
        assertThat(receipts.map { it.key() }).containsExactly("order-7", "order-7")
        assertThat(receipts.map { json.deserialize(it.value(), Receipt::class.java) })
            .containsExactly(Receipt("pay-1", "order-7", 4200), Receipt("pay-2", "order-7", 800))
        assertThat(ledger.totalCents("order-7")).isEqualTo(5000)
    }
}
