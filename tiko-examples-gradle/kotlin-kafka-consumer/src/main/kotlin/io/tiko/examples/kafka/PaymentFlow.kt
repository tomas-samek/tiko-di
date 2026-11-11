package io.tiko.examples.kafka

import io.tiko.EventTriggerGuard
import io.tiko.Scope
import io.tiko.annotations.Component
import io.tiko.annotations.EventHandler
import io.tiko.annotations.EventTrigger
import io.tiko.kafka.annotations.KafkaSink
import io.tiko.kafka.annotations.KafkaSource

/** `payments` topic in: each record becomes a [PaymentReceived] event on the local bus. */
@Component(scope = Scope.SINGLETON)
class PaymentConsumer {

    @KafkaSource(topic = "payments")
    @EventTrigger(eventName = "PaymentReceived")
    fun fromKafka(payment: PaymentReceived): PaymentReceived = payment
}

/**
 * Records each payment once. Kafka delivers at least once, so a payment can arrive again;
 * the ledger notices, and [FirstDeliveryOnly] stops the duplicate from going further.
 */
@Component(scope = Scope.SINGLETON)
class PaymentHandler(private val ledger: Ledger) {

    @EventHandler
    @EventTrigger(eventName = "PaymentRecorded", guard = [FirstDeliveryOnly::class])
    fun onPayment(payment: PaymentReceived): PaymentRecorded =
        PaymentRecorded(payment, firstDelivery = ledger.record(payment))
}

class FirstDeliveryOnly : EventTriggerGuard {
    override fun shouldTrigger(handlerResult: Any?, originalEvent: Any?): Boolean =
        handlerResult is PaymentRecorded && handlerResult.firstDelivery
}

/** `receipts` topic out: one [Receipt] per newly recorded payment, keyed by the order. */
@Component(scope = Scope.SINGLETON)
class ReceiptPublisher {

    @KafkaSink(topic = "receipts", partitionKey = "orderId")
    fun publish(recorded: PaymentRecorded): Receipt =
        recorded.payment.let { Receipt(it.paymentId, it.orderId, it.amountCents) }
}
