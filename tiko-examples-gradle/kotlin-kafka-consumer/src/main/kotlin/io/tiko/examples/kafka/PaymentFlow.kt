package io.tiko.examples.kafka

import io.tiko.EventTriggerGuard
import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.EventHandler
import io.tiko.annotations.EventTrigger
import io.tiko.kafka.annotations.KafkaSink
import io.tiko.kafka.annotations.KafkaSource

/**
 * `payments` topic in: each record is written to the [Ledger] and becomes a [PaymentRecorded]
 * event on the local bus.
 *
 * The bridge is a `suspend fun`, because the ledger's write is a suspending call. Tiko runs it on
 * the consumer thread in a unit of work of its own, and commits the record's offset once the
 * bridge has returned and the event's handlers have run.
 */
@Component(scope = Scope.SINGLETON)
class PaymentConsumer(private val ledger: Ledger) {

    @KafkaSource(topic = "payments")
    @EventTrigger(eventName = "PaymentRecorded")
    suspend fun fromKafka(payment: PaymentReceived): PaymentRecorded =
        PaymentRecorded(payment, firstDelivery = ledger.record(payment))
}

/**
 * Lets each payment through once. Kafka delivers at least once, so a payment can arrive again;
 * the ledger notices, and [FirstDeliveryOnly] stops the duplicate from going further.
 */
@Component(scope = Scope.SINGLETON)
class PaymentHandler {

    @EventHandler
    @EventTrigger(eventName = "PaymentAccepted", guard = [FirstDeliveryOnly::class])
    fun onRecorded(recorded: PaymentRecorded): PaymentAccepted = PaymentAccepted(recorded.payment)
}

class FirstDeliveryOnly : EventTriggerGuard {
    override fun shouldTrigger(handlerResult: Any?, originalEvent: Any?): Boolean =
        originalEvent is PaymentRecorded && originalEvent.firstDelivery
}

/** `receipts` topic out: one [Receipt] per newly recorded payment, keyed by the order. */
@Component(scope = Scope.SINGLETON)
class ReceiptPublisher {

    @KafkaSink(topic = "receipts", partitionKey = "orderId")
    fun publish(accepted: PaymentAccepted): Receipt =
        accepted.payment.let { Receipt(it.paymentId, it.orderId, it.amountCents) }
}
